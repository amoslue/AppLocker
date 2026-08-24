package com.example.focuslock

import android.content.Context
import java.net.IDN
import java.net.URI

object WebsiteBlockStore {
    val suggestions = listOf(
        "instagram.com",
        "tiktok.com",
        "facebook.com",
        "x.com",
        "reddit.com",
        "youtube.com"
    )

    private const val PREFS_NAME = "website_blocking"
    private const val KEY_DOMAINS = "domains"

    fun domains(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_DOMAINS, emptySet())
            ?.toSortedSet()
            .orEmpty()
    }

    fun add(context: Context, input: String): String? {
        val domain = normalize(input) ?: return null
        save(context, domains(context) + domain)
        return domain
    }

    fun remove(context: Context, domain: String) {
        save(context, domains(context) - domain)
    }

    fun isBlocked(host: String, blockedDomains: Set<String>): Boolean {
        val normalizedHost = normalize(host) ?: return false
        return blockedDomains.any { domain ->
            normalizedHost == domain || normalizedHost.endsWith(".$domain")
        }
    }

    fun normalize(input: String): String? {
        val cleaned = input.trim()
            .lowercase()
            .removePrefix("*.")
            .trimEnd('*')
            .trimEnd('.')
        if (cleaned.isBlank()) return null

        val host = runCatching {
            val value = if ("://" in cleaned) cleaned else "https://$cleaned"
            URI(value).host
        }.getOrNull()?.removePrefix("www.") ?: return null

        val ascii = runCatching { IDN.toASCII(host) }.getOrNull() ?: return null
        val labels = ascii.split('.')
        if (labels.size < 2 || labels.any { label ->
                label.isBlank() || label.length > 63 ||
                    label.startsWith('-') || label.endsWith('-') ||
                    label.any { !it.isLetterOrDigit() && it != '-' }
            }
        ) return null
        return ascii
    }

    private fun save(context: Context, domains: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_DOMAINS, domains)
            .apply()
    }
}
