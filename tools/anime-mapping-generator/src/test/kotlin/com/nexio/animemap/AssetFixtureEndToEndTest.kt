package com.nexio.animemap

import com.nexio.animemap.model.NexioAnimeMap
import com.nexio.animemap.model.NexioAnimeMapJsonAdapter
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AssetFixtureEndToEndTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun copyResource(name: String, dest: File) {
        val bytes = javaClass.getResource("/fixtures/$name")!!.readBytes()
        dest.writeBytes(bytes)
    }

    @Test fun `mha resources project to tvdb seasons one through three`() {
        val fribbIn = tmp.newFile("fribb.json")
        val scudleeIn = tmp.newFile("scudlee.xml")
        val overlayIn = tmp.newFile("overlay.json")
        val assetOut = File(tmp.root, "asset.json")
        val provOut = File(tmp.root, "prov.json")

        copyResource("fribb-mha-and-one-piece.json", fribbIn)
        copyResource("mha-eight-seasons.xml", scudleeIn)
        copyResource("overlay-examples.json", overlayIn)

        Generator.run(Generator.Args(
            fribbInput = fribbIn, scudleeInput = scudleeIn, overlayInput = overlayIn,
            assetOutput = assetOut, provenanceOutput = provOut,
            fribbUrl = "fribb://test", fribbCommit = "fcommit",
            scudleeUrl = "scudlee://test", scudleeCommit = "scommit"
        ))

        assertTrue("asset must exist", assetOut.exists())
        val asset = NexioAnimeMapJsonAdapter(Moshi.Builder().build()).fromJson(assetOut.readText())!!
        assertEquals(2, asset.schemaVersion)
        val mhaS1 = asset.identityRecordsByKitsu["11469"]!!
        assertEquals("1", mhaS1.tvdbSeason)
        val mhaS3 = asset.identityRecordsByKitsu["13881"]!!
        assertEquals("3", mhaS3.tvdbSeason)
    }

    @Test fun `byTvdb groups mha kitsu ids together`() {
        val fribbIn = tmp.newFile("fribb.json")
        val scudleeIn = tmp.newFile("scudlee.xml")
        val overlayIn = tmp.newFile("overlay.json")
        val assetOut = File(tmp.root, "asset.json")
        val provOut = File(tmp.root, "prov.json")
        copyResource("fribb-mha-and-one-piece.json", fribbIn)
        copyResource("mha-eight-seasons.xml", scudleeIn)
        copyResource("overlay-examples.json", overlayIn)
        Generator.run(Generator.Args(fribbIn, scudleeIn, overlayIn, assetOut, provOut,
            "f://", null, "s://", null))
        val asset = NexioAnimeMapJsonAdapter(Moshi.Builder().build()).fromJson(assetOut.readText())!!
        val mhaList = asset.indexes.byTvdb["305074"]!!
        assertTrue(mhaList.contains("11469"))
        assertTrue(mhaList.contains("13881"))
    }

    @Test fun `provenance file captures source commits and overlay version`() {
        val fribbIn = tmp.newFile("fribb.json")
        val scudleeIn = tmp.newFile("scudlee.xml")
        val overlayIn = tmp.newFile("overlay.json")
        val assetOut = File(tmp.root, "asset.json")
        val provOut = File(tmp.root, "prov.json")
        copyResource("fribb-mha-and-one-piece.json", fribbIn)
        copyResource("mha-eight-seasons.xml", scudleeIn)
        copyResource("overlay-examples.json", overlayIn)
        Generator.run(Generator.Args(fribbIn, scudleeIn, overlayIn, assetOut, provOut,
            "https://fribb", "abc123", "https://scudlee", "def456"))
        assertNotNull(provOut.readText())
        assertTrue(provOut.readText().contains("abc123"))
        assertTrue(provOut.readText().contains("def456"))
    }
}
