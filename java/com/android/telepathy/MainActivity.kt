package com.android.telepathy

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.android.telepathy.databinding.ActivityMainBinding
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Request
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null

    private val contentId = "1271432825"

    private val manifestUrl = "https://bitmovin-a.akamaihd.net/content/art-of-motion_drm/mpds/11331.mpd"
    private val licenseServerUrl = "https://cwip-shaka-proxy.appspot.com/no_auth"

    private val widevineUuid = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    private var availableVideoTracks: List<Tracks.Group> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        if (player == null) {
            // --- Track Selector for Resolution Selection ---
            val trackSelector = DefaultTrackSelector(this).apply {}

            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .build()
                .also { exoPlayer ->
                    binding.playerView.player = exoPlayer
                    // --- Media Item Configuration ---
                    val mediaItem = MediaItem.Builder()
                        .setUri(manifestUrl)
                        .setMimeType(MimeTypes.APPLICATION_MPD) // Specify DASH manifest
                        .setDrmConfiguration(
                            MediaItem.DrmConfiguration.Builder(widevineUuid)
                                .setLicenseUri(licenseServerUrl)
                                .build()
                        )
                        .build()

                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true // Start playback automatically

                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            super.onPlayerError(error)
                            Toast.makeText(this@MainActivity, "Player Error: ${error.message}", Toast.LENGTH_LONG).show()
                            Log.e("PlayerError", "Error: ", error)
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            super.onTracksChanged(tracks)
                            updateResolutionSpinner(tracks, trackSelector)
                        }
                    })
                }
        }
    }

    @OptIn(UnstableApi::class)
    private fun updateResolutionSpinner(tracks: Tracks, trackSelector: DefaultTrackSelector) {
        availableVideoTracks = tracks.groups.filter {
            it.type == C.TRACK_TYPE_VIDEO && it.isSupported
        }

        binding.resolutionSpinner.visibility = View.VISIBLE

        val resolutionNames = mutableListOf<String>()
        resolutionNames.add(getString(R.string.default_resolution)) // Option for adaptive streaming

        availableVideoTracks.forEach { group ->
            for (i in 0 until group.length) {
                if (group.isTrackSupported(i)) {
                    val format = group.getTrackFormat(i)
                    resolutionNames.add("${format.width}x${format.height} (${format.bitrate / 1000} kbps)")
                }
            }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutionNames)
        binding.resolutionSpinner.adapter = adapter

        binding.resolutionSpinner.setSelection(0)


        binding.resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            @OptIn(UnstableApi::class)
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val parametersBuilder = trackSelector.buildUponParameters()
                if (position == 0) {
                    parametersBuilder.setMaxVideoSizeSd()
                } else {
                    val selectedTrackGroupIndex = (position -1) / availableVideoTracks[0].length
                    val selectedTrackInGroupIndex = (position -1) % availableVideoTracks[0].length

                    if (selectedTrackGroupIndex < availableVideoTracks.size) {
                        val trackGroup = availableVideoTracks[selectedTrackGroupIndex]
                        if (selectedTrackInGroupIndex < trackGroup.length) {
                            // Handling max video size to the selected screen configuration
                            val format = trackGroup.getTrackFormat(selectedTrackInGroupIndex)
                            parametersBuilder.setMaxVideoSize(format.width, format.height)
                        }
                    }
                    binding.resolutionSpinner.setSelection(position)
                }
                trackSelector.parameters = parametersBuilder.build()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }


    private fun releasePlayer() {
        player?.let { exoPlayer ->
            exoPlayer.release()
            player = null
        }
    }

    override fun onStart() {
        Log.d("onStart : ", "inside")
        super.onStart()
        if (player == null) {
            initializePlayer()
        }
        hotstarData()
    }

    override fun onResume() {
        super.onResume()
        if (player == null) {
            initializePlayer()
        } else {
            player?.play()
        }
    }

    override fun onPause() {
        super.onPause()

        // Pause playback when the activity is not visible
        player?.pause()
    }

    override fun onStop() {
        super.onStop()

        // Ensure player is always released on onStop if we don't want background playback
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Ensure player is always released on onDestroy
        releasePlayer()
    }

    //Assignment 2
    private fun fetchHotstarData(): NetworkResult<HotstarContent> {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://www.hotstar.com/api/internal/bff/v2/pages/1021/spaces/3854?content_id=$contentId&mode=default&offset=0&page_enum=watch&size=10&tabName=movie")
            .addHeader("accept", "application/json, text/plain, */*")
            .addHeader("accept-language", "eng")
            .addHeader("referer", "https://www.hotstar.com/in/movies/valiant-one/$contentId/watch")
            .addHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36 Edg/138.0.0.0")
            .addHeader("x-country-code", "in")
            .addHeader("x-hs-app", "250627001")
            .addHeader("x-hs-client", "platform:web;app_version:25.06.27.1;browser:Edge;schema_version:0.0.1508;os:Windows;os_version:10;browser_version:138;network_data:4g")
            .addHeader("x-hs-device-id", "621e9-8dc156-8e7145-3b4aa8")
            .addHeader("x-hs-platform", "web")
            .addHeader("x-hs-usertoken",
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhcHBJZCI6IiIsImF1ZCI6InVtX2FjY2VzcyIsImV4cCI6MTc1MjQ5NzQxMiwiaWF0IjoxNzUyNDExMDEyLCJpc3MiOiJUUyIsImp0aSI6IjlhMDM4YzA4ZjUxNjQzNjU5NzdjMTU4ZWM0ZWRmZjJhIiwic3ViIjoie1wiaElkXCI6XCJhNGFmOWFiNjYwNDY0Y2Q4OWZhZWQwYjJiZWRiOTIwYVwiLFwicElkXCI6XCI0ODRiMGI1NmFkODA0ZmZhODUzZDAzMWYzYWFkYzdjOVwiLFwiZHdIaWRcIjpcIjdkZGRlN2MyMWUwMTM0OWVhMWFjYTI5MDU4ODMwZjQ2MDNkZWZmZjYwNTc1MjJkYWU1N2EzMjUyYzg0Mjk5NGZcIixcImR3UGlkXCI6XCIxYmRhOGQ4ZTBhZmU4ZDcwYjAzNDY0YzQzZDIyYTQ2NzlkNDA0M2FlNTdmMmZjZWNmZDg4MmFlYzFkZGY4NTZhXCIsXCJvbGRIaWRcIjpcImE0YWY5YWI2NjA0NjRjZDg5ZmFlZDBiMmJlZGI5MjBhXCIsXCJvbGRQaWRcIjpcIjQ4NGIwYjU2YWQ4MDRmZmE4NTNkMDMxZjNhYWRjN2M5XCIsXCJpc1BpaVVzZXJNaWdyYXRlZFwiOmZhbHNlLFwibmFtZVwiOlwiU2FoaWxcIixcInBob25lXCI6XCI5NzgxOTYyNDY3XCIsXCJpcFwiOlwiMjQwMTo0OTAwOjFjYzQ6ZTBhOTpkMGM2OjFmMjo1YmEyOjY2OTlcIixcImNvdW50cnlDb2RlXCI6XCJpblwiLFwiY3VzdG9tZXJUeXBlXCI6XCJudVwiLFwidHlwZVwiOlwicGhvbmVcIixcImlzRW1haWxWZXJpZmllZFwiOmZhbHNlLFwiaXNQaG9uZVZlcmlmaWVkXCI6dHJ1ZSxcImRldmljZUlkXCI6XCI2MjFlOS04ZGMxNTYtOGU3MTQ1LTNiNGFhOFwiLFwicHJvZmlsZVwiOlwiQURVTFRcIixcInZlcnNpb25cIjpcInYyXCIsXCJzdWJzY3JpcHRpb25zXCI6e1wiaW5cIjp7XCJIb3RzdGFyU3VwZXJBZHNGcmVlXCI6e1wic3RhdHVzXCI6XCJTXCIsXCJleHBpcnlcIjpcIjIwMjYtMDYtMjBUMDg6MjQ6MTUuMDAwWlwiLFwic2hvd0Fkc1wiOlwiMFwiLFwiY250XCI6XCIxXCJ9LFwiSG90c3Rhck1vYmlsZVwiOntcInN0YXR1c1wiOlwiU1wiLFwiZXhwaXJ5XCI6XCIyMDI1LTEwLTAyVDE2OjMzOjI2LjAwMFpcIixcInNob3dBZHNcIjpcIjFcIixcImNudFwiOlwiMVwifX19LFwiZW50XCI6XCJDZ3NTQ1FnRE9BTkFBVkM0Q0FxOUFRb0ZDZ01LQVFVU3N3RVNCMkZ1WkhKdmFXUVNBMmx2Y3hJRGQyVmlFZ2xoYm1SeWIybGtkSFlTQm1acGNtVjBkaElIWVhCd2JHVjBkaElFYlhkbFloSUhkR2w2Wlc1MGRoSUZkMlZpYjNNU0JtcHBiM04wWWhJRWNtOXJkUklIYW1sdkxXeDVaaElLWTJoeWIyMWxZMkZ6ZEJJRWRIWnZjeElFY0dOMGRoSURhbWx2RWdaclpYQnNaWElhQW5Oa0dnSm9aQm9EWm1oa0lnTnpaSElxQm5OMFpYSmxieW9JWkc5c1luazFMakVxQ21SdmJHSjVRWFJ0YjNOWUFRcnRBUW9GQ2dNS0FRQVM0d0VTQjJGdVpISnZhV1FTQTJsdmN4SURkMlZpRWdsaGJtUnliMmxrZEhZU0JtWnBjbVYwZGhJSFlYQndiR1YwZGhJRWJYZGxZaElIZEdsNlpXNTBkaElGZDJWaWIzTVNCbXBwYjNOMFloSUVjbTlyZFJJSGFtbHZMV3g1WmhJS1kyaHliMjFsWTJGemRCSUVkSFp2Y3hJRWNHTjBkaElEYW1sdkVnWnJaWEJzWlhJU0JIaGliM2dTQzNCc1lYbHpkR0YwYVc5dUVneHFhVzl3YUc5dVpXeHBkR1VTRFdabFlYUjFjbVZ0YjJKcGJHVWFBbk5rR2dKb1pCb0RabWhrSWdOelpISXFCbk4wWlhKbGJ5b0laRzlzWW5rMUxqRXFDbVJ2YkdKNVFYUnRiM05ZQVFvaUNob0tDQ0lHWm1seVpYUjJDZzRTQlRVMU9ETTJFZ1UyTkRBME9SSUVPR1JZQVJKckNBRVE4TytWcnBvekdsTUtJVWh2ZEhOMFlYSlRkWEJsY2k1QlpITkdjbVZsTGtsT0xsbGxZWEl1TVRBNU9SSVRTRzkwYzNSaGNsTjFjR1Z5UVdSelJuSmxaUm9FVTJWc1ppQ1k5OERqK0RJb21NK0ZvZTR6TUFjNEFVRHVEeWdCUWdjb21QZkE0L2d5U0FFPVwiLFwiaXNzdWVkQXRcIjoxNzUyNDExMDEyMzU4LFwibWF0dXJpdHlMZXZlbFwiOlwiQVwiLFwiZHBpZFwiOlwiNDg0YjBiNTZhZDgwNGZmYTg1M2QwMzFmM2FhZGM3YzlcIixcInN0XCI6MSxcImRhdGFcIjpcIkNnUUlBQklBQ2dRSUFDb0FDaElJQUNJT2dBRVZpQUVCa0FHVDhvR0N1QzBLQkFnQU9nQUtCQWdBTWdBS0JBZ0FRZ0E9XCJ9IiwidmVyc2lvbiI6IjFfMCJ9.Wxko4nWxNfBTmZxUQdltSFSzd_1phS-QpYcAr9m6L_c")
            .addHeader("cookie",
                "sessionUserUP=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhcHBJZCI6IiIsImF1ZCI6InVtX2FjY2VzcyIsImV4cCI6MTc1MjQ5NzQxMiwiaWF0IjoxNzUyNDExMDEyLCJpc3MiOiJUUyIsImp0aSI6IjlhMDM4YzA4ZjUxNjQzNjU5NzdjMTU4ZWM0ZWRmZjJhIiwic3ViIjoie1wiaElkXCI6XCJhNGFmOWFiNjYwNDY0Y2Q4OWZhZWQwYjJiZWRiOTIwYVwiLFwicElkXCI6XCI0ODRiMGI1NmFkODA0ZmZhODUzZDAzMWYzYWFkYzdjOVwiLFwiZHdIaWRcIjpcIjdkZGRlN2MyMWUwMTM0OWVhMWFjYTI5MDU4ODMwZjQ2MDNkZWZmZjYwNTc1MjJkYWU1N2EzMjUyYzg0Mjk5NGZcIixcImR3UGlkXCI6XCIxYmRhOGQ4ZTBhZmU4ZDcwYjAzNDY0YzQzZDIyYTQ2NzlkNDA0M2FlNTdmMmZjZWNmZDg4MmFlYzFkZGY4NTZhXCIsXCJvbGRIaWRcIjpcImE0YWY5YWI2NjA0NjRjZDg5ZmFlZDBiMmJlZGI5MjBhXCIsXCJvbGRQaWRcIjpcIjQ4NGIwYjU2YWQ4MDRmZmE4NTNkMDMxZjNhYWRjN2M5XCIsXCJpc1BpaVVzZXJNaWdyYXRlZFwiOmZhbHNlLFwibmFtZVwiOlwiU2FoaWxcIixcInBob25lXCI6XCI5NzgxOTYyNDY3XCIsXCJpcFwiOlwiMjQwMTo0OTAwOjFjYzQ6ZTBhOTpkMGM2OjFmMjo1YmEyOjY2OTlcIixcImNvdW50cnlDb2RlXCI6XCJpblwiLFwiY3VzdG9tZXJUeXBlXCI6XCJudVwiLFwidHlwZVwiOlwicGhvbmVcIixcImlzRW1haWxWZXJpZmllZFwiOmZhbHNlLFwiaXNQaG9uZVZlcmlmaWVkXCI6dHJ1ZSxcImRldmljZUlkXCI6XCI2MjFlOS04ZGMxNTYtOGU3MTQ1LTNiNGFhOFwiLFwicHJvZmlsZVwiOlwiQURVTFRcIixcInZlcnNpb25cIjpcInYyXCIsXCJzdWJzY3JpcHRpb25zXCI6e1wiaW5cIjp7XCJIb3RzdGFyU3VwZXJBZHNGcmVlXCI6e1wic3RhdHVzXCI6XCJTXCIsXCJleHBpcnlcIjpcIjIwMjYtMDYtMjBUMDg6MjQ6MTUuMDAwWlwiLFwic2hvd0Fkc1wiOlwiMFwiLFwiY250XCI6XCIxXCJ9LFwiSG90c3Rhck1vYmlsZVwiOntcInN0YXR1c1wiOlwiU1wiLFwiZXhwaXJ5XCI6XCIyMDI1LTEwLTAyVDE2OjMzOjI2LjAwMFpcIixcInNob3dBZHNcIjpcIjFcIixcImNudFwiOlwiMVwifX19LFwiZW50XCI6XCJDZ3NTQ1FnRE9BTkFBVkM0Q0FxOUFRb0ZDZ01LQVFVU3N3RVNCMkZ1WkhKdmFXUVNBMmx2Y3hJRGQyVmlFZ2xoYm1SeWIybGtkSFlTQm1acGNtVjBkaElIWVhCd2JHVjBkaElFYlhkbFloSUhkR2w2Wlc1MGRoSUZkMlZpYjNNU0JtcHBiM04wWWhJRWNtOXJkUklIYW1sdkxXeDVaaElLWTJoeWIyMWxZMkZ6ZEJJRWRIWnZjeElFY0dOMGRoSURhbWx2RWdaclpYQnNaWElhQW5Oa0dnSm9aQm9EWm1oa0lnTnpaSElxQm5OMFpYSmxieW9JWkc5c1luazFMakVxQ21SdmJHSjVRWFJ0YjNOWUFRcnRBUW9GQ2dNS0FRQVM0d0VTQjJGdVpISnZhV1FTQTJsdmN4SURkMlZpRWdsaGJtUnliMmxrZEhZU0JtWnBjbVYwZGhJSFlYQndiR1YwZGhJRWJYZGxZaElIZEdsNlpXNTBkaElGZDJWaWIzTVNCbXBwYjNOMFloSUVjbTlyZFJJSGFtbHZMV3g1WmhJS1kyaHliMjFsWTJGemRCSUVkSFp2Y3hJRWNHTjBkaElEYW1sdkVnWnJaWEJzWlhJU0JIaGliM2dTQzNCc1lYbHpkR0YwYVc5dUVneHFhVzl3YUc5dVpXeHBkR1VTRFdabFlYUjFjbVZ0YjJKcGJHVWFBbk5rR2dKb1pCb0RabWhrSWdOelpISXFCbk4wWlhKbGJ5b0laRzlzWW5rMUxqRXFDbVJ2YkdKNVFYUnRiM05ZQVFvaUNob0tDQ0lHWm1seVpYUjJDZzRTQlRVMU9ETTJFZ1UyTkRBME9SSUVPR1JZQVJKckNBRVE4TytWcnBvekdsTUtJVWh2ZEhOMFlYSlRkWEJsY2k1QlpITkdjbVZsTGtsT0xsbGxZWEl1TVRBNU9SSVRTRzkwYzNSaGNsTjFjR1Z5UVdSelJuSmxaUm9FVTJWc1ppQ1k5OERqK0RJb21NK0ZvZTR6TUFjNEFVRHVEeWdCUWdjb21QZkE0L2d5U0FFPVwiLFwiaXNzdWVkQXRcIjoxNzUyNDExMDEyMzU4LFwibWF0dXJpdHlMZXZlbFwiOlwiQVwiLFwiZHBpZFwiOlwiNDg0YjBiNTZhZDgwNGZmYTg1M2QwMzFmM2FhZGM3YzlcIixcInN0XCI6MSxcImRhdGFcIjpcIkNnUUlBQklBQ2dRSUFDb0FDaElJQUNJT2dBRVZpQUVCa0FHVDhvR0N1QzBLQkFnQU9nQUtCQWdBTWdBS0JBZ0FRZ0E9XCJ9IiwidmVyc2lvbiI6IjFfMCJ9.Wxko4nWxNfBTmZxUQdltSFSzd_1phS-QpYcAr9m6L_c; expires=Mon, 14-Jul-2025 12:50:13 GMT; path=/in; secure")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d("hotstarData", "Request failed with code: ${response.code}")
                NetworkResult.Error("Request failed", response.code)
            } else {
                val json = response.body?.string()
                if (json != null) {
                    val mapper = jacksonObjectMapper()
                    val response1 = mapper.readValue<MoreLikeThisApiResponse>(json)

                    try {
                        val items = response1.success
                            ?.space
                            ?.widgetWrappers?.firstOrNull() // Assuming one widget_wrapper for this tray
                            ?.widget
                            ?.data
                            ?.items
                        var title =""
                        var description =""
                        if (items != null) {
                            for (item in items) {
                                val contentInfo = item.verticalContentPoster
                                    ?.data
                                    ?.expandedContentPoster
                                    ?.contentInfo

                                if (contentInfo != null) {
                                    title = contentInfo.title ?: "N/A"
                                    description = contentInfo.description ?: "N/A"
                                    if (title != "N/A") { // Ensure we actually found the target
                                        NetworkResult.Success(HotstarContent(title, description))
                                    }
                                }
                            }
                        }
                        NetworkResult.Success(HotstarContent(title, description))
                    } catch (e: Exception) {
                        Log.e("hotstarData", "JSON parsing error", e)
                        NetworkResult.Error("Error parsing response: ${e.message}")
                    }
                } else {
                    Log.d("hotstarData", "Response body is null")
                    NetworkResult.Error("Empty response body")
                }
            }
        } catch (e: Exception) {
            Log.e("hotstarData", "Network request error", e)
            NetworkResult.Error("Network error: ${e.message}")
        }
    }

    private fun hotstarData() {
        // Launch a coroutine tied to the Activity's lifecycle
        lifecycleScope.launch {
            binding.contentValues.visibility = View.GONE // Hide previous values

            val result: NetworkResult<HotstarContent> = withContext(Dispatchers.IO) {
                // This block runs on a background IO thread
                fetchHotstarData()
            }

            when (result) {
                is NetworkResult.Success -> {
                    val content = result.data
                    binding.contentValues.text = "Title: ${content.title}\nDescription: ${content.description}"
                    binding.contentValues.visibility = View.VISIBLE
                }
                is NetworkResult.Error -> {
                    binding.contentValues.text = "Error: ${result.message}"
                    binding.contentValues.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, "Error: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MoreLikeThisApiResponse(
    @JsonProperty("success") val success: MltSuccessData?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MltSuccessData(
    @JsonProperty("space") val space: MltSpaceData?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MltSpaceData(
    @JsonProperty("widget_wrappers") val widgetWrappers: List<MltWidgetWrapper>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MltWidgetWrapper(
    @JsonProperty("widget") val widget: MltScrollableTrayWidget?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MltScrollableTrayWidget(
    // We are interested in the 'data' part of this widget
    @JsonProperty("data") val data: MltTrayData?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MltTrayData(
    @JsonProperty("items") val items: List<MltTrayItem>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MltTrayItem(
    // This item contains the actual content poster details
    @JsonProperty("vertical_content_poster") val verticalContentPoster: MltVerticalContentPoster?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MltVerticalContentPoster(
    // The details are within the 'data' of the vertical_content_poster
    @JsonProperty("data") val data: MltPosterData?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MltPosterData(
    // And finally, the expanded_content_poster holds the title and description
    @JsonProperty("expanded_content_poster") val expandedContentPoster: MltExpandedContentPoster?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MltExpandedContentPoster(
    @JsonProperty("content_info") val contentInfo: MltContentInfo?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MltContentInfo(
    @JsonProperty("title") val title: String?,
    @JsonProperty("description") val description: String?
    // You could add 'tags' List<Map<String, String>>? if needed
)

data class HotstarContent(
    val title: String,
    val description: String
)

sealed class NetworkResult<out T> {
    data class Success<out T>(val data: T) : NetworkResult<T>()
    data class Error(val message: String, val code: Int? = null) : NetworkResult<Nothing>()
}
