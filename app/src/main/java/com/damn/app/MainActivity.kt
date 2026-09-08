package com.damn.app

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.damn.app.databinding.ActivityMainBinding
import com.damn.app.ui.DashboardFragment
import com.damn.app.ui.HomeFragment
import com.damn.app.ui.SettingsFragment
import com.damn.app.util.Prefs
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme(this)
        applyPortraitLock()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Register listener for real-time orientation updates
        getSharedPreferences("damn_prefs", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(this)

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val isTablet = resources.getBoolean(R.bool.isTablet)
        binding.bottomNav.visibility = if (isTablet) View.GONE else View.VISIBLE
        binding.navRail.visibility = if (isTablet) View.VISIBLE else View.GONE

        // ViewPager2 Setup
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 2 // Keep all 3 pages alive for smooth transitions

        val navListener = NavigationBarView.OnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> binding.viewPager.setCurrentItem(0, true)
                R.id.nav_dashboard -> binding.viewPager.setCurrentItem(1, true)
                R.id.nav_settings -> binding.viewPager.setCurrentItem(2, true)
            }
            true
        }

        binding.bottomNav.setOnItemSelectedListener(navListener)
        binding.navRail.setOnItemSelectedListener(navListener)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNav.menu.getItem(position).isChecked = true
                binding.navRail.menu.getItem(position).isChecked = true
            }
        })

        // Apply window insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, 0)
            binding.bottomNav.setPadding(0, 0, 0, sys.bottom)
            binding.navRail.setPadding(0, sys.top, 0, sys.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        applyPortraitLock()
    }

    override fun onDestroy() {
        getSharedPreferences("damn_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "force_portrait") {
            applyPortraitLock()
        }
    }

    private fun applyPortraitLock() {
        if (Prefs.isForcePortrait(this)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            setNavVisibility(false)
        }
    }

    fun setNavVisibility(visible: Boolean) {
        if (visible) {
            val isTablet = resources.getBoolean(R.bool.isTablet)
            binding.bottomNav.visibility = if (isTablet) View.GONE else View.VISIBLE
            binding.navRail.visibility = if (isTablet) View.VISIBLE else View.GONE
        } else {
            binding.bottomNav.visibility = View.GONE
            binding.navRail.visibility = View.GONE
        }
    }

    private class MainPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> HomeFragment()
                1 -> DashboardFragment()
                2 -> SettingsFragment()
                else -> HomeFragment()
            }
        }
    }
}
