package com.example.colorcircle // Make sure this matches your package name

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.colorcircle.databinding.ActivityMainBinding // This class is auto-generated
import java.util.Random

// seems like the above is just importing libraries

class MainActivity : AppCompatActivity() {

    // 1. Declare the binding variable and a Random object
    private lateinit var binding: ActivityMainBinding
    private val random = Random()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 2. Inflate the layout
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3. Set the click listener on the circle
        binding.circleView.setOnClickListener {
            // 4. Generate a new random color
            val newColor = Color.rgb(
                random.nextInt(256), // Random Red
                random.nextInt(256), // Random Green
                random.nextInt(256)  // Random Blue
            )

            // 5. Apply the new color to the circle's background
            val background = binding.circleView.background as GradientDrawable
            background.setColor(newColor)
        }
    }
}

