package org.feeluown.mobile

interface IosFileMusicOutput {
    fun tracksJson(): String
}

object EmptyIosFileMusicOutput : IosFileMusicOutput {
    override fun tracksJson(): String = """{"tracks":[]}"""
}
