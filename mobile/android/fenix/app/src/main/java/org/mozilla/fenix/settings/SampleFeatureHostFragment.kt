package org.mozilla.fenix.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import mozilla.components.lib.state.Store
import org.mozilla.fenix.R
import org.mozilla.tabstray.TabsTrayHost
import org.mozilla.tabstray.TabsTrayState
import org.mozilla.tabstray.TabsTrayUi
import org.mozilla.tabstray.tabs
import org.mozilla.tabstray.tabsTrayReducer

/**
 * A simple [Fragment] subclass.
 * Use the [SampleFeatureHostFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class SampleFeatureHostFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_sample_feature_host, container, false)
        view.findViewById<ComposeView>(R.id.compose_view).setContent {
            TabsTrayHost(Store(TabsTrayState(tabs = tabs), ::tabsTrayReducer))
        }
        return view
    }
}
