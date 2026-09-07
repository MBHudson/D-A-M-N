package com.damn.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.damn.app.databinding.ActivityMainBinding
import com.damn.app.util.Prefs
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val isTablet = resources.getBoolean(R.bool.isTablet)
        binding.bottomNav.visibility = if (isTablet) android.view.View.GONE else android.view.View.VISIBLE
        binding.navRail.visibility = if (isTablet) android.view.View.VISIBLE else android.view.View.GONE

        // ViewPager2 Setup
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 2 // Keep all 3 pages alive for smooth transitions

        val navListener = com.google.android.material.navigation.NavigationBarView.OnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> binding.viewPager.setCurrentItem(0, true)
                R.id.nav_dashboard -> binding.viewPager.setCurrentItem(1, true)
                R.id.nav_settings -> binding.viewPager.setCurrentItem(2, true)
            }
            true
        }

        binding.bottomNav.setOnItemSelectedListener(navListener)
        binding.navRail.setOnItemSelectedListener(navListener)

        binding.viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNav.menu.getItem(position).isChecked = true
                binding.navRail.menu.getItem(position).isChecked = true
            }
        })

        // Apply window insets
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, 0)
            binding.bottomNav.setPadding(0, 0, 0, sys.bottom)
            binding.navRail.setPadding(0, sys.top, 0, sys.bottom)
            insets
        }
    }

    fun setNavVisibility(visible: Boolean) {
        if (visible) {
            val isTablet = resources.getBoolean(R.bool.isTablet)
            binding.bottomNav.visibility = if (isTablet) android.view.View.GONE else android.view.View.VISIBLE
            binding.navRail.visibility = if (isTablet) android.view.View.VISIBLE else android.view.View.GONE
        } else {
            binding.bottomNav.visibility = android.view.View.GONE
            binding.navRail.visibility = android.view.View.GONE
        }
    }

    private class MainPagerAdapter(fa: androidx.fragment.app.FragmentActivity) : androidx.viewpager2.adapter.FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): androidx.fragment.app.Fragment {
            return when (position) {
                0 -> com.damn.app.ui.HomeFragment()
                1 -> com.damn.app.ui.DashboardFragment()
                2 -> com.damn.app.ui.SettingsFragment()
                else -> com.damn.app.ui.HomeFragment()
            }
        }
    }
}
