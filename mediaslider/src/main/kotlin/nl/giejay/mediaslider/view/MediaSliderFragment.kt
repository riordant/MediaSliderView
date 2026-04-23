package nl.giejay.mediaslider.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.media3.datasource.DefaultHttpDataSource
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItemViewHolder

open class MediaSliderFragment : Fragment() {
    var onAssetFavoriteChanged: ((SliderItemViewHolder, Boolean) -> Unit)? = null
        set(value) {
            field = value
            (this.view as? MediaSliderView)?.onAssetFavoriteChanged = value
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return MediaSliderView(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.requestFocus()
        (view as? MediaSliderView)?.onAssetFavoriteChanged = onAssetFavoriteChanged
    }

    override fun onPause() {
        destroyMediaPlayer()
        super.onPause()
    }

    private fun destroyMediaPlayer() {
        view.onDestroy()
    }

    override fun getView(): MediaSliderView {
        return super.getView() as MediaSliderView
    }

    fun loadMediaSliderView(config: MediaSliderConfiguration) {
        view.loadMediaSliderView(config)
    }

    fun setDefaultExoFactory(defaultExoFactory: DefaultHttpDataSource.Factory) {
        view.setDefaultExoFactory(defaultExoFactory)
    }
}
