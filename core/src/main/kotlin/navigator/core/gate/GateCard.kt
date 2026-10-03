package navigator.core.gate

/**
 * The confirmation-card payload (vision 06 §2): class name in plain words, site + URL,
 * target field/button, one-line consequence. Pure data + builder — NO UI code here; the
 * overlay (Phase 9) renders these strings verbatim over Chrome.
 */
data class GateCard(
    val classLabel: String,
    val site: String,
    val url: String,
    val target: String,
    val consequenceLine: String,
) {
    companion object {
        /**
         * Builds the card for a classified action. The typed value ([GateAction.textToType])
         * is deliberately NEVER part of the card — secrets never render on the overlay.
         */
        fun build(hclass: HClass, action: GateAction, context: PageContext): GateCard {
            val site = context.site
            return GateCard(
                classLabel = hclass.plainLabel,
                site = site,
                url = context.url,
                target = targetDescription(action),
                consequenceLine = consequence(hclass, site),
            )
        }

        /** Plain-words target: visible label (or id when blank), node id, action kind. */
        private fun targetDescription(action: GateAction): String {
            val label = action.target.label.ifBlank { action.target.id }
            return "\"$label\" (id ${action.target.id}, ${action.kind.name.lowercase()})"
        }

        /** One-line consequence per class, in the vision's plain-words voice. */
        private fun consequence(hclass: HClass, site: String): String = when (hclass) {
            HClass.H1_PAYMENT -> "Will enter or submit payment details on $site."
            HClass.H2_PURCHASE_CONFIRM -> "Will confirm a purchase on $site."
            HClass.H3_PASSWORD_CHANGE -> "Will enter or change a password on $site."
            HClass.H4_LOGIN -> "Will submit login credentials to $site."
            HClass.H5_ACCOUNT -> "Will create or delete an account on $site."
            HClass.H6_UPLOAD -> "Will upload a file from this device to $site."
            HClass.H7_DOWNLOAD -> "Will start a download from $site."
            HClass.H8_CREDENTIAL_TYPING -> "Will type a credential into a field on $site."
        }
    }
}
