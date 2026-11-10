package com.nuvio.tv.youtube

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class TvYouTubeViewModel @Inject constructor(
    @ApplicationContext context: Context,
) : YouTubeViewModel(context, autoSelect = true)

@HiltViewModel
class TvYouTubeAccountViewModel @Inject constructor(
    @ApplicationContext context: Context,
) : YouTubeAccountViewModel(context)
