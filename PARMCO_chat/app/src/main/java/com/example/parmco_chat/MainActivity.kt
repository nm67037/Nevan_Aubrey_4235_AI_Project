package com.example.parmco_chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.parmco_chat.ui.theme.PARMCO_chatTheme
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PARMCO_chatTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .wrapContentSize(Alignment.Center)
                    ) {
                        ColorChangingCircle()
                    }
                }
            }
        }
    }
}

@Composable
fun ColorChangingCircle() {
    var targetColor by remember { mutableStateOf(Color.Red) }
    val animatedColor by animateColorAsState(targetValue = targetColor)

    Box(
        modifier = Modifier
            .size(200.dp)
            .clip(CircleShape)
            .background(animatedColor)
            .clickable {
                // Change to a random color each tap
                targetColor = Color(
                    red = Random.nextFloat(),
                    green = Random.nextFloat(),
                    blue = Random.nextFloat(),
                    alpha = 1f
                )
            }
    )
}

@Preview(showBackground = true)
@Composable
fun PreviewColorChangingCircle() {
    PARMCO_chatTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.Center)
        ) {
            ColorChangingCircle()
        }
    }
}
