package nl.giejay.mediaslider.view

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.viewpager.widget.ViewPager
import com.google.common.collect.Iterables
import com.google.common.collect.Lists
import com.zeuskartik.mediaslider.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.mediaslider.adapter.AlignOption
import nl.giejay.mediaslider.adapter.MetaDataAdapter
import nl.giejay.mediaslider.adapter.MetaDataClock
import nl.giejay.mediaslider.adapter.MetaDataMediaCount
import nl.giejay.mediaslider.adapter.ScreenSlidePagerAdapter
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.util.FixedSpeedScroller
import nl.giejay.mediaslider.util.MediaSliderListener
import timber.log.Timber
import java.lang.reflect.Field


class MediaSliderView(context: Context) : ConstraintLayout(context) {
    // view elements
    private var playButton: View
    private var favoriteIndicator: ImageView
    private var mainHandler: Handler
    private var mPager: ViewPager
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                if (currentItemType() == SliderItemType.VIDEO && currentPlayerInScope?.isPlaying == true && currentPlayerInScope?.volume == 0f) {
                    Timber.i("Volume changed detected, unmuting video")
                    Toast.makeText(context, "Volume changed detected, unmuting video", Toast.LENGTH_SHORT).show()
                    currentPlayerView?.findViewById<ImageButton>(R.id.exo_mute)?.performClick()
                }
            }
        }
    }

    // config
    private lateinit var config: MediaSliderConfiguration
    private lateinit var metaDataLeftAdapter: MetaDataAdapter
    private lateinit var metaDataRightAdapter: MetaDataAdapter

    /// internal
    private var currentPlayerInScope: ExoPlayer? = null
    private var currentPlayerView: PlayerView? = null
    private var defaultExoFactory = DefaultHttpDataSource.Factory()
    private var slideShowPlaying = false
    private val goToNextAssetRunnable = Runnable { this.goToNextAsset() }
    private var pagerAdapter: ScreenSlidePagerAdapter? = null
    private var loading = false
    private val ioScope = CoroutineScope(Job() + Dispatchers.IO)
    private val transformResults = mutableMapOf<Int, String>()
    private var currentToast: Toast? = null
    private var longPressHandled = false
    private var currentVideoAssetId: String? = null
    private var lastSavedPlaybackPositionSeconds: Int? = null
    private var playbackSaveRunnable: Runnable? = null
    private var seekSaveRunnable: Runnable? = null
    private var resumeLoadToken = 0
    private var consumeNextVideoControlsUpKeyUp = false
    var onLongPressCenterListener: (() -> Unit)? = null
    var onAssetFavoriteChanged: ((SliderItemViewHolder, Boolean) -> Unit)? = null
    var onSliderItemSelected: ((SliderItemViewHolder) -> Unit)? = null
    var getVideoPlaybackPositionSeconds: (suspend (String) -> Int?)? = null
    var onVideoPlaybackPositionChanged: ((String, Int) -> Unit)? = null

    init {
        inflate(getContext(), R.layout.slider, this)

        playButton = findViewById(R.id.playPause)
        favoriteIndicator = findViewById(R.id.favorite_indicator)
        playButton.setOnClickListener { toggleSlideshow(true) }
        mPager = findViewById(R.id.pager)
        mainHandler = Handler(Looper.getMainLooper())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        context.registerReceiver(volumeReceiver, filter)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.unregisterReceiver(volumeReceiver)
    }

    @OptIn(UnstableApi::class)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (mPager.adapter == null) {
            return super.dispatchKeyEvent(event)
        }

        if (
            consumeNextVideoControlsUpKeyUp &&
            event.action == KeyEvent.ACTION_UP &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        ) {
            consumeNextVideoControlsUpKeyUp = false
            return true
        }

        if (isCenterKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) {
                longPressHandled = false
            } else if (event.action == KeyEvent.ACTION_DOWN && event.isLongPress && !longPressHandled) {
                longPressHandled = true
                onLongPressCenterListener?.invoke() ?: return super.dispatchKeyEvent(event)
                return true
            }
        }

        val itemType = currentItemType()
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (context is MediaSliderListener && (context as MediaSliderListener).onButtonPressed(event)) {
                return false
            } else if (handleVideoDpadSeekControls(event.keyCode, itemType)) {
                return true
            } else if ((event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                if (itemType == SliderItemType.IMAGE) {
                    toggleSlideshow(true)
                } else if (currentPlayerView != null) {
                    return super.dispatchKeyEvent(event)
                }
                return false
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && itemType == SliderItemType.VIDEO && currentPlayerView != null) {
                currentPlayerView!!.useController = true
                currentPlayerView!!.showController()

                // Ensure proper focus to fix highlighting issue on first open
                currentPlayerView!!.post {
                    val progressView = currentPlayerView!!.findViewById<View>(R.id.exo_progress_layout)
                    progressView?.requestFocus()
                    // Force a refresh of the focus state
                    progressView?.invalidate()
                }

                return super.dispatchKeyEvent(event)
            } else if (event.keyCode == KeyEvent.KEYCODE_BACK && itemType == SliderItemType.VIDEO && currentPlayerView != null && currentPlayerView!!.isControllerFullyVisible) {
                currentPlayerView!!.hideController()
                return true
            } else if (slideShowPlaying && itemType == SliderItemType.IMAGE) {
                if (event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                    toggleSlideshow(true)
                } else {
                    // remove all current callbacks to prevent multiple runnables
                    mainHandler.removeCallbacks(goToNextAssetRunnable)
                    goToNextAsset()
                    return false
                }
                return super.dispatchKeyEvent(event)
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (itemType == SliderItemType.IMAGE || currentPlayerView?.isControllerFullyVisible == false) {
                    goToNextAsset()
                } else if (currentPlayerView?.isControllerFullyVisible == true) {
                    return super.dispatchKeyEvent(event)
                }
                return false
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (itemType == SliderItemType.IMAGE || currentPlayerView?.isControllerFullyVisible == false) {
                    goToPreviousAsset()
                    return false
                }
                return super.dispatchKeyEvent(event)
            }
        }
        return if (itemType == SliderItemType.IMAGE) false else super.dispatchKeyEvent(event)
    }

    private fun handleVideoDpadSeekControls(keyCode: Int, itemType: SliderItemType): Boolean {
        if (!config.useVideoDpadSeekControls || itemType != SliderItemType.VIDEO || currentPlayerView == null) {
            return false
        }

        val playerView = currentPlayerView ?: return false
        val player = currentPlayerInScope ?: return false

        if (playerView.isControllerFullyVisible) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && isFocusedOnVideoControlButton(playerView)) {
                playerView.hideController()
                playerView.requestFocus()
                consumeNextVideoControlsUpKeyUp = true
                return true
            }

            if (isFocusedOnVideoControlEdge(playerView, keyCode)) {
                return true
            }

            return false
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekCurrentVideoBy(-VIDEO_DPAD_SEEK_MS)
                showVideoControls(focusPlayPauseButton = false)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekCurrentVideoBy(VIDEO_DPAD_SEEK_MS)
                showVideoControls(focusPlayPauseButton = false)
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showVideoControls(focusPlayPauseButton = true)
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (player.playbackState == Player.STATE_ENDED) {
                        player.seekTo(0)
                    }
                    player.play()
                }
                showVideoControls(focusPlayPauseButton = true)
                true
            }

            else -> false
        }
    }

    private fun showVideoControls(focusPlayPauseButton: Boolean) {
        val playerView = currentPlayerView ?: return
        playerView.useController = true
        playerView.showController()
        playerView.post {
            val focusView = if (focusPlayPauseButton) {
                playerView.findViewById<View>(R.id.exo_pause)
            } else {
                playerView.findViewById<View>(R.id.exo_progress_layout)
            }

            focusView?.requestFocus()
            focusView?.invalidate()
        }
    }

    private fun isFocusedOnVideoControlButton(playerView: PlayerView): Boolean {
        val focusedId = playerView.findFocus()?.id ?: return false
        return focusedId == R.id.exo_mute ||
                focusedId == R.id.exo_rewind ||
                focusedId == R.id.exo_restart ||
                focusedId == R.id.exo_pause ||
                focusedId == R.id.exo_forward ||
                focusedId == R.id.exo_slideshow
    }

    private fun isFocusedOnVideoControlEdge(playerView: PlayerView, keyCode: Int): Boolean {
        val focusedId = playerView.findFocus()?.id ?: return false
        return (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && focusedId == R.id.exo_mute) ||
                (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focusedId == R.id.exo_restart)
    }

    private fun seekCurrentVideoBy(deltaMs: Long) {
        val player = currentPlayerInScope ?: return
        val duration = player.duration.takeIf { it > 0 }
        val target = (player.currentPosition + deltaMs).let { position ->
            if (duration != null) {
                position.coerceIn(0L, duration)
            } else {
                position.coerceAtLeast(0L)
            }
        }

        player.seekTo(target)
        scheduleSeekSettledSave()
    }

    private fun isCenterKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
    }

    private fun goToPreviousAsset() {
        mPager.setCurrentItem((if (0 == mPager.currentItem) mPager.adapter!!.count else mPager.currentItem) - 1,
            config.enableSlideAnimation)
    }

    fun loadMediaSliderView(config: MediaSliderConfiguration) {
        this.config = config

        val listViewRight = findViewById<ListView>(R.id.metadata_view_right)
        metaDataRightAdapter = MetaDataAdapter(context,
            config.metaDataConfig.filter { it.align == AlignOption.RIGHT },
            config.metaDataConfig.map { it.withAlign(align = AlignOption.RIGHT) }.distinct(),
            { if (currentItem().hasSecondaryItem()) currentItem().secondaryItem!! else currentItem().mainItem },
            { currentItem().hasSecondaryItem() })
        listViewRight.divider = null
        listViewRight.adapter = metaDataRightAdapter

        val listViewLeft = findViewById<ListView>(R.id.metadata_view_left)
        metaDataLeftAdapter = MetaDataAdapter(context,
            config.metaDataConfig.filter { it.align == AlignOption.LEFT },
            // don't show the clock/media count twice in portrait mode and force everything to be left aligned
            config.metaDataConfig.filterNot { it is MetaDataClock || it is MetaDataMediaCount }
                .map { it.withAlign(align = AlignOption.LEFT) }.distinct(),
            { currentItem().mainItem },
            { currentItem().hasSecondaryItem() })
        listViewLeft.divider = null
        listViewLeft.adapter = metaDataLeftAdapter

        val listener: ExoPlayerListener = object : ExoPlayerListener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    stopPlaybackSaveTimer()
                    persistCurrentVideoPlaybackPosition(0)
                    if (slideShowPlaying) {
                        goToNextAsset()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val playPauseButton = findViewById<ImageButton>(R.id.exo_pause)
                playPauseButton?.setImageResource(if (isPlaying) R.drawable.exo_legacy_controls_pause else R.drawable.exo_legacy_controls_play)

                // Handle screen wake lock for video playback
                if (currentItemType() == SliderItemType.VIDEO) {
                    if (isPlaying) {
                        startPlaybackSaveTimer()
                        setKeepScreenOnFlags()
                    } else {
                        stopPlaybackSaveTimer()
                        persistCurrentVideoPlaybackPosition()
                        clearKeepScreenOnFlags()
                    }
                }
            }

            override fun onPositionDiscontinuity(reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    scheduleSeekSettledSave()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (slideShowPlaying) {
                    goToNextAsset()
                }
                // Clear screen wake lock on error
                if (currentItemType() == SliderItemType.VIDEO) {
                    stopPlaybackSaveTimer()
                    clearKeepScreenOnFlags()
                }
            }
        }
        initViewsAndSetAdapter(listener)
    }

    private fun setStartPosition() {
        if (config.startPosition >= 0) {
            if (config.startPosition > config.items.size) {
                mPager.currentItem = (config.items.size - 1)
                return
            }
            mPager.currentItem = config.startPosition
        } else {
            mPager.currentItem = 0
        }
        mPager.offscreenPageLimit = 1
    }

    fun toggleSlideshow(togglePlayButton: Boolean) {
        slideShowPlaying = !slideShowPlaying
        if (slideShowPlaying) {
            // do not start timers for videos, they will continue in the player listener
            if (currentItemType() == SliderItemType.IMAGE) {
                startTimerNextAsset()
            }
            setKeepScreenOnFlags();
        } else {
            clearKeepScreenOnFlags()
            mainHandler.removeCallbacks(goToNextAssetRunnable)
        }
        if (togglePlayButton) {
            togglePlayButton()
        }
    }

    private fun togglePlayButton() {
        playButton.visibility = VISIBLE
        playButton.setBackgroundResource(if (slideShowPlaying) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
        mainHandler.postDelayed({
            playButton.visibility = GONE
        }, 2000)
    }

    private fun startTimerNextAsset() {
        mainHandler.removeCallbacks(goToNextAssetRunnable)
        mainHandler.postDelayed(goToNextAssetRunnable, (config.interval * 1000).toLong())
    }

    private fun startPlaybackSaveTimer() {
        stopPlaybackSaveTimer()
        val runnable = object : Runnable {
            override fun run() {
                persistCurrentVideoPlaybackPosition()
                playbackSaveRunnable = this
                mainHandler.postDelayed(this, PLAYBACK_SAVE_INTERVAL_MS)
            }
        }
        playbackSaveRunnable = runnable
        mainHandler.postDelayed(runnable, PLAYBACK_SAVE_INTERVAL_MS)
    }

    private fun stopPlaybackSaveTimer() {
        playbackSaveRunnable?.let { mainHandler.removeCallbacks(it) }
        playbackSaveRunnable = null
    }

    private fun scheduleSeekSettledSave() {
        seekSaveRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            seekSaveRunnable = null
            persistCurrentVideoPlaybackPosition()
        }
        seekSaveRunnable = runnable
        mainHandler.postDelayed(runnable, SEEK_SETTLED_SAVE_DELAY_MS)
    }

    private fun clearSeekSettledSave() {
        seekSaveRunnable?.let { mainHandler.removeCallbacks(it) }
        seekSaveRunnable = null
    }

    private fun resolvePlaybackPositionToPersist(player: ExoPlayer): Int? {
        val currentPositionSeconds = (player.currentPosition / 1000).toInt()
        val durationSeconds = player.duration.takeIf { it > 0 }?.let { (it / 1000).toInt() }

        if (durationSeconds != null && durationSeconds - currentPositionSeconds <= PLAYBACK_COMPLETE_THRESHOLD_SECONDS) {
            return 0
        }

        return currentPositionSeconds.takeIf { it > 0 }
    }

    private fun persistCurrentVideoPlaybackPosition(positionSeconds: Int? = currentPlayerInScope?.let { resolvePlaybackPositionToPersist(it) }) {
        val assetId = currentVideoAssetId ?: return
        val position = positionSeconds ?: return

        if (position == lastSavedPlaybackPositionSeconds) {
            return
        }

        lastSavedPlaybackPositionSeconds = position
        onVideoPlaybackPositionChanged?.invoke(assetId, position)
    }

    private fun goToNextAsset() {
        if (mPager.currentItem < mPager.adapter!!.count - 1) {
            mPager.setCurrentItem(mPager.currentItem + 1, config.enableSlideAnimation)
        } else {
            mPager.setCurrentItem(0, config.enableSlideAnimation)
        }
    }

    private fun initViewsAndSetAdapter(listener: ExoPlayerListener) {
        pagerAdapter = ScreenSlidePagerAdapter(
            context,
            config.items,
            config,
            { mPager.currentItem },
            { result, position -> transformResults[position] = result },
            {
                when (it) {
                    R.id.exo_rewind -> goToPreviousAsset()
                    R.id.exo_restart -> restartCurrentVideo()
                    R.id.exo_forward -> goToNextAsset()
                    R.id.exo_slideshow -> toggleSlideshow(true)
                }
            },
            listener
        )

        try {
            if (config.enableSlideAnimation && config.animationSpeedMillis > 0) {
                val mScroller: Field = ViewPager::class.java.getDeclaredField("mScroller")
                mScroller.isAccessible = true
                val scroller = FixedSpeedScroller(mPager.context, DecelerateInterpolator(0.75F), config.animationSpeedMillis)
                // scroller.setFixedDuration(5000);
                mScroller.set(mPager, scroller)
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not set scroller")
        }

        mPager.setAdapter(pagerAdapter)
        setStartPosition()
        updateFavoriteIcon()
        mPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(sliderItemIndex: Int, v: Float, i1: Int) {
                if (config.loadMore != null && mPager.currentItem > config.items.size - 10 && !loading) {
                    // do not load more in the "background" because "addItemsMain" will invoke the onPageScrolled again and reload the current item
                    loading = true

                    ioScope.launch {
                        val nextItems = config.loadMore!!.invoke()
                        addItemsMain(nextItems)
                        // keep loading until no more items are received, so set it to false if there are items
                        loading = nextItems.isEmpty()
                    }
                } else {
                    stopPlayer()
                    if (sliderItemIndex != mPager.currentItem) {
                        return
                    }
                    val sliderItem = config.items[sliderItemIndex]
                    val mainItem = sliderItem.mainItem

                    updateMetaData(metaDataLeftAdapter, mainItem, sliderItemIndex)
                    updateMetaData(metaDataRightAdapter, if (sliderItem.hasSecondaryItem()) sliderItem.secondaryItem!! else mainItem, sliderItemIndex)

                    config.onAssetSelected(sliderItem)
                    onSliderItemSelected?.invoke(sliderItem)
                    currentToast?.cancel()
                    if (!sliderItem.hasSecondaryItem() && config.debugEnabled && transformResults.contains(sliderItemIndex)) {
                        currentToast = Toast.makeText(context, transformResults[sliderItemIndex], Toast.LENGTH_LONG)
                        currentToast!!.show()
                        transformResults.remove(sliderItemIndex)
                    }

                    val statusLayoutLeft = findViewById<LinearLayout>(R.id.meta_data_holder)
                    if (sliderItem.type == SliderItemType.VIDEO) {
                        if (config.isGradiantOverlayVisible) {
                            statusLayoutLeft.background = null
                        }
                        val viewTag = mPager.findViewWithTag<ExoPlayerView>("view$sliderItemIndex") ?: return
                        if(!viewTag.isReady()){
                            Timber.e("Player is not initialized properly, cannot play video.")
                            Toast.makeText(context, "Player is not initialized properly, cannot play video.", Toast.LENGTH_LONG).show()
                            return
                        }
                        currentPlayerView = viewTag.getPlayerView()
                        currentPlayerInScope = viewTag.getPlayer()
                        currentVideoAssetId = mainItem.id
                        if (currentPlayerInScope!!.playbackState == Player.STATE_IDLE && sliderItem.url != null) {
                            prepareVideoWithResume(mainItem.id, sliderItem.url!!, currentPlayerInScope!!)
                        } else {
                            if (!config.isVideoSoundEnable) {
                                currentPlayerView!!.player!!.volume = 0f
                            }
                            currentPlayerInScope!!.playWhenReady = true
                        }
                    } else {
                        if (config.isGradiantOverlayVisible) {
                            statusLayoutLeft.setBackgroundResource(R.drawable.gradient_overlay)
                        }
                        if (slideShowPlaying) {
                            startTimerNextAsset()
                        }
                        stopPlayer()
                    }
                }
            }

            private fun updateMetaData(adapter: MetaDataAdapter, sliderItem: SliderItem, sliderItemIndex: Int) {
                adapter.getItemsToShow().forEachIndexed { metaDataIndex, item ->
                    if (adapter.hasStateForItem(sliderItem.id, metaDataIndex)) {
                        // already have state for this item, no need to fetch again
                        return@forEachIndexed
                    }
                    ioScope.launch {
                        val value = item.getValue(context, sliderItem, sliderItemIndex, config.items.size)
                        adapter.updateState(sliderItem.id, metaDataIndex, value)
                        withContext(Dispatchers.Main) {
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }

            override fun onPageSelected(i: Int) {
                metaDataRightAdapter.notifyDataSetChanged()
                metaDataLeftAdapter.notifyDataSetChanged()
                updateFavoriteIcon()
            }

            override fun onPageScrollStateChanged(i: Int) {
            }
        })
    }

    private fun prepareVideoWithResume(assetId: String, mediaUrl: String, player: ExoPlayer) {
        val token = ++resumeLoadToken
        val playbackPositionProvider = getVideoPlaybackPositionSeconds

        if (playbackPositionProvider == null) {
            lastSavedPlaybackPositionSeconds = null
            prepareMedia(mediaUrl, player, defaultExoFactory)
            if (!config.isVideoSoundEnable) {
                currentPlayerView?.player?.volume = 0f
            }
            player.playWhenReady = true
            return
        }

        ioScope.launch {
            val savedPositionSeconds = try {
                playbackPositionProvider(assetId)
            } catch (e: Exception) {
                Timber.e(e, "Could not load saved playback position for $assetId")
                null
            }

            withContext(Dispatchers.Main) {
                if (token != resumeLoadToken || currentVideoAssetId != assetId || currentPlayerInScope != player) {
                    return@withContext
                }

                val startPositionMs = savedPositionSeconds
                    ?.takeIf { it > 0 }
                    ?.toLong()
                    ?.times(1000L) ?: 0L

                lastSavedPlaybackPositionSeconds = savedPositionSeconds
                prepareMedia(mediaUrl, player, defaultExoFactory, startPositionMs)
                if (!config.isVideoSoundEnable) {
                    currentPlayerView?.player?.volume = 0f
                }
                player.playWhenReady = true
            }
        }
    }

    private fun restartCurrentVideo() {
        val player = currentPlayerInScope ?: return
        player.seekTo(0)
        player.play()
        persistCurrentVideoPlaybackPosition(0)
        scheduleSeekSettledSave()
    }

    fun onDestroy() {
        persistCurrentVideoPlaybackPosition()
        stopPlaybackSaveTimer()
        clearSeekSettledSave()
        if (currentPlayerInScope != null) {
            currentPlayerInScope!!.release()
        }
        clearKeepScreenOnFlags()
        mainHandler.removeCallbacks(goToNextAssetRunnable)
    }

    private fun clearKeepScreenOnFlags() {
        if (context is Activity) {
            // view is being triggered from main app, remove the flags to keep screen on
            val window = (context as Activity).window
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d("MediaSliderView", "clear FLAG_KEEP_SCREEN_ON")
        }
    }

    private fun setKeepScreenOnFlags() {
        if (context is Activity) {
            // view is being triggered from main app, prevent app going to sleep
            val window = (context as Activity).window
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d("MediaSliderView", "set FLAG_KEEP_SCREEN_ON")
        }
    }

    private fun stopPlayer() {
        persistCurrentVideoPlaybackPosition()
        stopPlaybackSaveTimer()
        clearSeekSettledSave()
        resumeLoadToken += 1

        if (currentPlayerInScope != null && (currentPlayerInScope!!.isPlaying || currentPlayerInScope!!.isLoading)) {
            currentPlayerInScope!!.stop()
        }
        // Clear screen wake lock when stopping player
        if (currentItemType() == SliderItemType.VIDEO) {
            clearKeepScreenOnFlags()
        }
        currentVideoAssetId = null
        lastSavedPlaybackPositionSeconds = null
    }

    fun setDefaultExoFactory(defaultExoFactory: DefaultHttpDataSource.Factory) {
        this.defaultExoFactory = defaultExoFactory
    }

    suspend fun addItemsMain(items: List<SliderItemViewHolder>) = withContext(Dispatchers.Main) {
        addItems(items)
    }

    private fun addItems(items: List<SliderItemViewHolder>) {
        setItems(Lists.newArrayList(Iterables.concat(config.items, items)).distinct())
    }

    fun setItems(items: List<SliderItemViewHolder>) {
        if (items.isEmpty()) {
            Toast.makeText(context, "No items received to show in slideshow", Toast.LENGTH_SHORT).show()
            return
        }
        if (slideShowPlaying) {
            // to prevent timing issues when adding + sliding at the same time
            mainHandler.removeCallbacks(goToNextAssetRunnable)
        }
        config.items = items
        pagerAdapter!!.setItems(items)
        updateFavoriteIcon()
        if (slideShowPlaying && currentItemType() == SliderItemType.IMAGE) {
            startTimerNextAsset()
        }
    }

    private fun currentItem(): SliderItemViewHolder = config.items[mPager.currentItem]
    private fun currentItemOrNull(): SliderItemViewHolder? = if (::config.isInitialized) config.items.getOrNull(mPager.currentItem) else null
    private fun currentItemType(): SliderItemType = config.items[mPager.currentItem].type

    fun getCurrentIndex(): Int {
        return if (mPager.adapter == null) 0 else mPager.currentItem
    }

    fun updateFavoriteIcon() {
        val currentItem = currentItemOrNull()
        val shouldShow = currentItem?.let {
            it.mainItem.isFavorite || (it.secondaryItem?.isFavorite == true)
        } == true
        favoriteIndicator.visibility = if (shouldShow) VISIBLE else GONE
    }

    @OptIn(UnstableApi::class)
    fun isControllerVisible(): Boolean {
        return currentPlayerView?.isControllerFullyVisible == true
    }

    companion object {
        private const val PLAYBACK_SAVE_INTERVAL_MS = 5000L
        private const val SEEK_SETTLED_SAVE_DELAY_MS = 500L
        private const val PLAYBACK_COMPLETE_THRESHOLD_SECONDS = 5
        private const val VIDEO_DPAD_SEEK_MS = 5000L

        @SuppressLint("UnsafeOptInUsageError")
        fun prepareMedia(
            mediaUrl: String,
            player: ExoPlayer,
            factory: DefaultHttpDataSource.Factory,
            startPositionMs: Long = 0L
        ) {
            val mediaUri = Uri.parse(mediaUrl)
            val mediaItem = MediaItem.fromUri(mediaUri)
            val mediaSource = ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
            player.setMediaSource(mediaSource, startPositionMs)
            player.prepare()
        }
    }
}
