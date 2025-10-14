package model

data class Issue(
    val id: String,
    val summary: String,
    val description: String?,
    val state: String?
)

