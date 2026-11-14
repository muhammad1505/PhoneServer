package dev.pocketshare.ui.changelog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ChangelogRelease(
    val version: String,
    val date: String,
    val title: String,
    val highlights: List<String>,
)

/** Reads the bilingual changelog bundled in assets. Never throws; returns an empty list instead. */
fun loadChangelog(context: Context): List<ChangelogRelease> =
    runCatching {
        val text =
            context.assets
                .open(CHANGELOG_ASSET)
                .bufferedReader()
                .use { it.readText() }
        val releases = JSONObject(text).getJSONArray("releases")
        val indonesian =
            context.resources.configuration.locales[0].language.let {
                it == "in" || it == "id"
            }
        buildList {
            for (index in 0 until releases.length()) {
                val release = releases.getJSONObject(index)
                val highlights =
                    if (indonesian) {
                        release.optJSONArray("highlights_id") ?: release.optJSONArray("highlights")
                    } else {
                        release.optJSONArray("highlights")
                    } ?: JSONArray()
                add(
                    ChangelogRelease(
                        version = release.getString("version"),
                        date = release.optString("date"),
                        title =
                            if (indonesian) {
                                release.optString("title_id", release.optString("title"))
                            } else {
                                release.optString("title")
                            },
                        highlights =
                            buildList {
                                for (item in 0 until highlights.length()) {
                                    add(highlights.getString(item))
                                }
                            },
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

fun changelogVersion(versionName: String?): String = versionName.orEmpty().substringBefore('-')

private const val CHANGELOG_ASSET = "changelog.json"
