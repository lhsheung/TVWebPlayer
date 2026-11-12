package com.tvblplayer.core.api

import java.io.IOException

class BiliApiException(
    val apiCode: Int,
    val apiMessage: String,
) : IOException("Bili API code=$apiCode message=$apiMessage")

