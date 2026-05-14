package me.unknkriod.ang.enums

enum class RoutingType(val fileName: String) {
    WHITE_CHINA("custom_routing_white"),
    BLACK_CHINA("custom_routing_black"),
    GLOBAL("custom_routing_global"),
    WHITE_IRAN("custom_routing_white_iran"),
    WHITE_RUSSIA("custom_routing_white_russia");

    companion object {
        fun fromIndex(index: Int): RoutingType {
            return when (index) {
                0 -> WHITE_RUSSIA
                1 -> GLOBAL
                2 -> WHITE_CHINA
                3 -> BLACK_CHINA
                4 -> WHITE_IRAN
                else -> WHITE_RUSSIA
            }
        }
    }
}
