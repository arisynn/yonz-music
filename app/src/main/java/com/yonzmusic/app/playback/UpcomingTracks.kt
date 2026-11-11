package com.yonzmusic.app.playback

/** Follow the player's actual shuffle/repeat navigation, with no cycles/current-track duplication. */
internal object UpcomingTracks {
    const val MAX_AHEAD=4
    fun indices(current:Int,next:(Int)->Int):List<Int> {
        if(current<0)return emptyList()
        val visited=hashSetOf(current);val result=mutableListOf<Int>();var cursor=current
        repeat(MAX_AHEAD) {
            val index=next(cursor)
            if(index<0 || !visited.add(index))return result
            result+=index;cursor=index
        }
        return result
    }
}
