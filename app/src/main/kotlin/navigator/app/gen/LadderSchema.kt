package navigator.app.gen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.LadderValidationException
import navigator.core.ladder.LadderValidator

/**
 * The ladder wire schema (Phase 10, vision 04 §1 v1.4.0): parses the writer LLM's JSON
 * content into a typed [Ladder] and runs the FULL gen-boundary validation. Laws here:
 *
 * - the DONE-CONDITION WHITELIST (ART-4) is enforced at the schema boundary: a
 *   done-condition object must carry exactly one of `urlHost` / `markerText` /
 *   `elementPresent` / `count` — an opinion-phrased condition ("the page looks right")
   * has no whitelisted key and is REJECTED, loudly, before any navigation;
 * - structural violations (malformed JSON, wrong shapes, blank fields, oversized
 *   ladders) throw [LadderSchemaException]; the caller's bounded-retry path catches it
 *   (ONE retry), and a second failure is a clean pre-navigation refusal;
 * - the final gate is [LadderValidator.validate] — the same code-side validation matrix
 *   `:core` owns (caps, shape-vs-class, count bounds).
 *
 * Parsing discipline mirrors the wire client's: unknown FIELDS are ignored (provider
 * variance), missing/wrong-typed required fields are loud errors, never defaults.
 */
object LadderSchema {

    /** Why the writer's output was refused (the bounded-retry path's trigger). */
    class LadderSchemaException(message: String) : IllegalArgumentException(message)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses [content] (the assistant message body) into a validated [Ladder]. Accepts
     * the content wrapped in ```json code fences (deterministic strip); anything that is
     * not one JSON object at the core throws [LadderSchemaException].
     */
    fun parse(content: String, maxSubtasks: Int): Ladder {
        val stripped = stripFences(content).trim()
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw LadderSchemaException(
                "restructure output carries no JSON object (got ${content.take(120)}...) — " +
                    "schema-rejected before any navigation",
            )
        }
        val body = stripped.substring(start, end + 1)
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw LadderSchemaException("restructure output is malformed JSON: ${e.message}")
        }
        val summary = requiredString(root, "summary")
        val subtasksElement = root["subtasks"]
            ?: throw LadderSchemaException("restructure output is missing the required 'subtasks' array")
        val subtasksArray = try {
            subtasksElement.jsonArray
        } catch (e: Exception) {
            throw LadderSchemaException("'subtasks' must be an array")
        }
        if (subtasksArray.isEmpty()) {
            throw LadderSchemaException("'subtasks' is empty — a ladder without subtasks is incoherent")
        }
        if (subtasksArray.size > maxSubtasks) {
            throw LadderSchemaException(
                "${subtasksArray.size} subtasks exceed the $maxSubtasks-subtask cap (ART-5) — " +
                    "schema-rejected",
            )
        }
        val subtasks = subtasksArray.mapIndexed { index, element ->
            val obj = try {
                element.jsonObject
            } catch (e: Exception) {
                throw LadderSchemaException("subtask ${index + 1} is not an object")
            }
            navigator.core.ladder.Subtask(
                instruction = requiredString(obj, "instruction", "subtask ${index + 1}"),
                done = doneCondition(
                    obj["done"] as? JsonObject
                        ?: throw LadderSchemaException(
                            "subtask ${index + 1}: missing the required 'done' condition object",
                        ),
                    index = index + 1,
                ),
            )
        }
        val taskClass = parseTaskClass(root["taskClass"]?.let { it.jsonPrimitive.content })
        return try {
            LadderValidator.validate(Ladder(summary, subtasks, taskClass))
        } catch (e: LadderValidationException) {
            throw LadderSchemaException(e.message ?: "ladder validation failed")
        }
    }

    /**
     * The done-condition whitelist (ART-4): exactly one whitelisted key must be present.
     * A condition object with NONE of them — the opinion shape ("the page looks right")
     * — is rejected with the whitelist spelled out, and multiple keys are rejected too
     * (the schema is one shape per condition, never a blend).
     */
    /**
     * Parses ONE whitelisted done-condition out of the writer's `{"done": {...}}` repair
     * answer (Phase 10C, v1.7.0). Loud on a missing object, on any non-whitelisted shape,
     * and on multiple shapes at once — the same [doneCondition] law every ladder obeys.
     */
    fun parseDoneConditionText(text: String): DoneCondition {
        val root = Json.parseToJsonElement(text).jsonObject
        val done = root["done"] as? JsonObject
            ?: throw LadderSchemaException("a repaired done-condition must ride a 'done' object")
        return doneCondition(done, 0)
    }

    private fun doneCondition(obj: JsonObject, index: Int): DoneCondition {
        val keys = obj.keys
        fun exactlyOne(vararg names: String): Boolean = keys.count { it in names } == 1
        return when {
            exactlyOne("urlHost") -> {
                val host = requiredString(obj, "urlHost", "done-condition (subtask $index)")
                DoneCondition.UrlHost(host)
            }

            exactlyOne("urlContains") -> {
                val segment = requiredString(obj, "urlContains", "done-condition (subtask $index)")
                DoneCondition.UrlContains(segment)
            }

            exactlyOne("markerText") -> {
                val text = requiredString(obj, "markerText", "done-condition (subtask $index)")
                DoneCondition.MarkerText(text)
            }

            exactlyOne("elementPresent") -> {
                val desc = requiredString(obj, "elementPresent", "done-condition (subtask $index)")
                DoneCondition.ElementPresent(desc)
            }

            exactlyOne("answerExtracted") -> DoneCondition.AnswerExtracted

            exactlyOne("count") -> {
                val count = obj["count"] as? JsonObject
                    ?: throw LadderSchemaException(
                        "done-condition (subtask $index): 'count' must be an object {target, n}",
                    )
                val target = requiredString(count, "target", "count done-condition (subtask $index)")
                val n = (count["n"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw LadderSchemaException(
                        "count done-condition (subtask $index): missing integer field 'n'",
                    )
                DoneCondition.CountReached(target, n)
            }

            else -> throw LadderSchemaException(
                "done-condition (subtask $index) carries none of the whitelisted keys " +
                    "{urlHost, urlContains, markerText, elementPresent, count, answerExtracted} (got $keys) — an " +
                    "opinion-phrased done-condition is schema-REJECTED (ART-4); only " +
                    "machine-checkable conditions may ride a ladder",
            )
        }
    }

    private fun parseTaskClass(raw: String?): LadderTaskClass = when (raw?.trim()?.lowercase()) {
        "read", null, "" -> LadderTaskClass.READ
        "action" -> LadderTaskClass.ACTION
        "multi" -> LadderTaskClass.MULTI
        else -> throw LadderSchemaException(
            "taskClass '$raw' is not one of read|action|multi — schema-rejected",
        )
    }

    private fun requiredString(obj: JsonObject, key: String, what: String = "restructure output"): String {
        val primitive = obj[key] as? kotlinx.serialization.json.JsonPrimitive
            ?: throw LadderSchemaException("$what: missing required field '$key' — loud failure, never a default")
        return primitive.content
    }

    /** Deterministic code-fence strip: leading ``` fences (with an optional language tag). */
    private fun stripFences(content: String): String {
        var text = content.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```")
            text = text.substringAfter('\n', text)
            val closing = text.lastIndexOf("```")
            if (closing >= 0) text = text.substring(0, closing)
        }
        return text
    }
}
