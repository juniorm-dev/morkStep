package com.morkstep

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Health Connect rationale screen.
 *
 * When the app requests health permissions, Health Connect's permission
 * activity launches this activity first (via the
 * `android.health.connect.action.ACTION_SHOW_PERMISSIONS_RATIONALE`
 * intent filter) so the user learns why the data is needed before the grant
 * screen. "Continue" returns [Activity.RESULT_OK] and Health Connect proceeds
 * to the actual permission prompt; this activity finishes itself.
 */
class RationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "Heart rate from Health Connect",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "morkStep reads heart rate from Health Connect to fill in a finished " +
                                "workout's average, min and max when no watch or strap was connected.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = {
                                setResult(Activity.RESULT_OK)
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Continue")
                        }
                    }
                }
            }
        }
    }
}