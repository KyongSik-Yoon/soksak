package com.voicerewriter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicerewriter.ui.PlexMono

/**
 * Shows assets/third_party_notices.txt: the copyright notices and license texts that the
 * bundled MIT / Apache-2.0 / OFL components require us to hand to anyone who gets the APK.
 */
class LicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notices = assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() }
        setContent {
            com.voicerewriter.ui.OpenWisprTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(tr("Open source licenses"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                            Text(notices, fontFamily = PlexMono, fontSize = 11.sp, lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val NOTICES_ASSET = "third_party_notices.txt"
    }
}
