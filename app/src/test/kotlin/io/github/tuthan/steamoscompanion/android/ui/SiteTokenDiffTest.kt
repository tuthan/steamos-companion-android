package io.github.tuthan.steamoscompanion.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The token diff against the Decky site's `styles.css` (design profile, deliverable 5), run against the checked-in extract
 * `src/test/fixtures/phase06/site-tokens.json`, because builds and tests never read a sibling checkout. The extract is
 * written by `scripts/phase06-token-diff.py --write-extract` from the real stylesheet, which also diffs it directly:
 * run that script when the site changes.
 */
class SiteTokenDiffTest {
    private val extract = File("src/test/fixtures/phase06/site-tokens.json").readText()
    private val dark = CompanionColors.dark

    private val siteTokens: Map<String, Long> =
        Regex(""""(\w+)":\s*\{\s*"site":\s*"0x([0-9A-F]{8})"""").findAll(extract)
            .associate { it.groupValues[1] to it.groupValues[2].toLong(16) }

    private val appTokens: Map<String, Long> = mapOf(
        "bg" to dark.bg, "bgDeep" to dark.bgDeep, "panel" to dark.panel, "panel2" to dark.panel2,
        "line" to dark.line, "lineStrong" to dark.lineStrong, "text" to dark.text, "textSoft" to dark.textSoft,
        "textDim" to dark.textDim, "blue" to dark.blue, "blueBright" to dark.blueBright, "blueDeep" to dark.blueDeep,
        "green" to dark.green, "red" to dark.red, "amber" to dark.amber, "field" to dark.field, "slab" to dark.slab,
        "mono" to dark.mono, "navy" to dark.navy,
        "gradPrimaryStart" to dark.gradPrimaryStart, "gradPrimaryEnd" to dark.gradPrimaryEnd,
        "gradGreenStart" to dark.gradGreenStart, "gradGreenEnd" to dark.gradGreenEnd,
        "gradProgressStart" to dark.gradProgressStart,
    )

    /** The differences the profile documents, with the app values the profile's gradient table gives. */
    private val documentedDivergences = mapOf(
        "gradPrimaryStart" to 0xFF3579ABL,
        "gradPrimaryEnd" to 0xFF254F6EL,
        "gradGreenEnd" to 0xFF7FA82AL,
    )

    @Test fun theExtractIsAnUnmodifiedReadOfTheSiteAtThePin() {
        assertTrue(""""deckyPin": "9c213b31cdce36e67784355be21e2fc1b2b7570f"""" in extract)
        assertTrue("styles.css must be byte-identical to the pinned commit", """"stylesIdenticalToPin": true""" in extract)
        assertTrue(Regex(""""stylesSha256": "[0-9a-f]{64}"""").containsMatchIn(extract))
    }

    @Test fun everyColourTheAppTakesFromTheSiteMatchesItExceptTheDocumentedDivergences() {
        assertEquals("the extract covers exactly the tokens the diff knows", appTokens.keys, siteTokens.keys)
        val differences = siteTokens.filter { (token, site) -> appTokens.getValue(token) != site }.keys
        assertEquals(documentedDivergences.keys, differences)
    }

    @Test fun theDocumentedDivergencesAreExactlyTheProfilesDarkerButtonStops() {
        documentedDivergences.forEach { (token, expected) -> assertEquals(token, expected, appTokens.getValue(token)) }
        assertEquals("the extract lists the same divergences", documentedDivergences.keys,
            Regex(""""(\w+)":\s*"app stop""").findAll(extract).map { it.groupValues[1] }.toSet())
    }

    @Test fun theLightPaletteSharesTheNonColourTokensAndTheSameGradients() {
        // The site has no light mode; the light palette is derived and keeps the dark gradients (profile, "Light").
        val light = CompanionColors.light
        assertEquals(dark.gradPrimaryStart, light.gradPrimaryStart)
        assertEquals(dark.gradPrimaryEnd, light.gradPrimaryEnd)
        assertEquals(dark.gradGreenStart, light.gradGreenStart)
        assertEquals(dark.gradGreenEnd, light.gradGreenEnd)
        assertEquals(dark.blueBright, light.blueBright)
        assertEquals(dark.navy, light.navy)
    }

    @Test fun everyOtherPaletteTokenIsAKnownSiteIndependentValue() {
        val fields = CompanionPalette::class.java.declaredFields.filter { it.type == Long::class.javaPrimitiveType }
            .map { it.name }.toSet()
        val notFromSite = fields - appTokens.keys
        // A new palette token has to be classified here: taken from the site (add it to the script) or app-only.
        assertEquals(setOf("white", "heading", "gradProgressEnd", "gradGreenLabel"), notFromSite)
        assertEquals(0xFFFFFFFFL, dark.white)
        assertEquals(0xFFFFFFFFL, dark.heading)
        assertEquals("the progress strip ends on the site's bright blue", dark.blueBright, dark.gradProgressEnd)
        assertEquals("the green gradient's label is the page colour (R10)", dark.bg, dark.gradGreenLabel)
    }
}
