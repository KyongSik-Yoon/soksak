package com.voicerewriter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicerewriter.ui.PlexMono
import java.io.IOException

/**
 * Shows assets/third_party_notices.txt — the copyright notices and license texts that the bundled
 * MIT / Apache-2.0 / OFL components require us to hand to anyone who gets the APK — followed by
 * Qualcomm's NOTICE file for the QNN runtime, which its licence says must not be removed
 * (copied out of the AAR at build time, see qnnNotice in app/build.gradle.kts).
 */
class LicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // dark status/nav bar icons in the light theme (the XML theme leaves them white)
        super.onCreate(savedInstanceState)
        val qnn = readAsset(QNN_NOTICE_ASSET)?.let { "$QNN_NOTICE_HEADING\n\n$it" }
        // About 4,000 lines once Qualcomm's file is in: laid out lazily, a paragraph per item.
        val blocks = listOfNotNull(readAsset(NOTICES_ASSET), qnn).flatMap { paragraphs(it) }
        setContent {
            com.voicerewriter.ui.SoksakTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(tr("Open source licenses"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        SelectionContainer {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                                items(blocks) { block ->
                                    Text(block, fontFamily = PlexMono, fontSize = 11.sp, lineHeight = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readAsset(name: String): String? =
        try { assets.open(name).bufferedReader().use { it.readText() } } catch (_: IOException) { null }

    companion object {
        const val NOTICES_ASSET = "third_party_notices.txt"
        const val QNN_NOTICE_ASSET = "qualcomm_qnn_notice.txt"
        const val QNN_NOTICE_HEADING =
            "========================================================================\n" +
                "Qualcomm AI Runtime (QNN) — NOTICE file, as shipped by Qualcomm\n" +
                "========================================================================"

        /** Splits on blank lines so each lazy item stays a readable size. */
        internal fun paragraphs(text: String): List<String> =
            text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trimEnd() }.filter { it.isNotBlank() }
    }
}
