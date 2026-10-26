package com.yonzmusic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yonzmusic.app.data.Track
import com.yonzmusic.app.playback.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun QueueSheet(tracks: List<Track>, state: PlaybackUi, connection: PlaybackConnection, close: () -> Unit) {
    val byId=remember(tracks){tracks.associateBy { it.id }}
    val keys=state.queueKeys.takeIf { it.size==state.queue.size } ?: state.queue.mapIndexed { i,id -> "$i:$id" }
    val latestKeys by rememberUpdatedState(keys)
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val snackbar=remember { SnackbarHostState() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var origin by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(1f) }
    var scrollOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(dragging) {
        while(dragging!=null) {
            val center=origin+offset-scrollOffset+rowHeight/2
            val info=list.layoutInfo
            val edge=80f
            val amount=when { center<info.viewportStartOffset+edge -> -14f;center>info.viewportEndOffset-edge -> 14f;else -> 0f }
            if(amount!=0f) { val actual=list.scrollBy(amount);scrollOffset+=actual;offset+=actual }
            delay(32)
        }
    }
    fun remove(key:String) {
        val receipt=connection.removeKey(key) ?: return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if(snackbar.showSnackbar("Dikeluarkan dari antrean","Urungkan",duration=SnackbarDuration.Short)==SnackbarResult.ActionPerformed)connection.restore(receipt)
        }
    }
    ModalBottomSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).navigationBarsPadding()) {
            SectionTitle("Antreanmu",if(state.shuffle)"Acak aktif · matikan untuk mengurutkan"else "${state.queue.size} lagu · tahan pegangan untuk mengurutkan")
            SnackbarHost(snackbar,Modifier.padding(horizontal=Design.md))
            if(keys.isEmpty())EmptyState("Lagu berikutnya pilihanmu","Tambahkan lagu ke antrean untuk mulai mendengarkan.","playlist")
            LazyColumn(state=list,modifier=Modifier.weight(1f),contentPadding=PaddingValues(bottom=Design.page)) {
                itemsIndexed(keys,key={_,key->key}) { index,key ->
                    val track=byId[state.queue[index]]
                    val swipe=rememberSwipeToDismissBoxState(confirmValueChange={ value -> if(value==SwipeToDismissBoxValue.EndToStart)remove(key);false })
                    SwipeToDismissBox(state=swipe,enableDismissFromStartToEnd=false,enableDismissFromEndToStart=dragging==null,
                        backgroundContent={ Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(Design.page),contentAlignment=Alignment.CenterEnd){Text("Keluarkan",color=MaterialTheme.colorScheme.onErrorContainer)} },
                        modifier=Modifier.zIndex(if(dragging==key)1f else 0f).graphicsLayer { if(dragging==key){translationY=offset;shadowElevation=8.dp.toPx()} }) {
                        Surface(color=if(dragging==key)MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface) {
                            Column {
                                if(index==state.index || index==state.index+1)Text(if(index==state.index)"SEDANG DIPUTAR"else "BERIKUTNYA",Modifier.padding(start=Design.page,top=Design.sm),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                val handle: @Composable () -> Unit = {
                                    val color=MaterialTheme.colorScheme.onSurfaceVariant
                                    var menu by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton({menu=true},Modifier.size(48.dp).semantics {
                                            contentDescription="Urutkan ${track?.title ?: "lagu"}"
                                            customActions=listOf(
                                                CustomAccessibilityAction("Pindahkan ke atas"){if(index>0)connection.moveKey(key,latestKeys[index-1]);true},
                                                CustomAccessibilityAction("Pindahkan ke bawah"){if(index<latestKeys.lastIndex)connection.moveKey(key,latestKeys[index+1]);true},
                                                CustomAccessibilityAction("Keluarkan dari antrean"){remove(key);true})
                                        }.pointerInput(key) {
                                            detectDragGesturesAfterLongPress(onDragStart={
                                                if(state.shuffle)return@detectDragGesturesAfterLongPress
                                                val row=list.layoutInfo.visibleItemsInfo.firstOrNull{it.key==key}
                                                if(row!=null){dragging=key;origin=row.offset.toFloat();rowHeight=row.size.toFloat();offset=0f;scrollOffset=0f}
                                            },onDragCancel={dragging=null;offset=0f},onDragEnd={
                                                val center=origin+offset-scrollOffset+rowHeight/2
                                                val target=list.layoutInfo.visibleItemsInfo.filter { it.key!=key }.minByOrNull { kotlin.math.abs((it.offset+it.size/2f)-center) }
                                                if(target!=null && kotlin.math.abs(offset)>rowHeight*.45f)connection.moveKey(key,target.key as String)
                                                dragging=null;offset=0f
                                            },onDrag={ change,delta -> change.consume();offset+=delta.y })
                                        }) {
                                            Canvas(Modifier.size(20.dp)){for(x in 0..1)for(y in 0..2)drawCircle(color,1.6.dp.toPx(),Offset(size.width*(.3f+x*.4f),size.height*(.2f+y*.3f)))}
                                        }
                                        DropdownMenu(menu,{menu=false}) {
                                            DropdownMenuItem(text={Text("Pindahkan ke atas")},enabled=!state.shuffle && index>0,onClick={connection.moveKey(key,keys[index-1]);menu=false})
                                            DropdownMenuItem(text={Text("Pindahkan ke bawah")},enabled=!state.shuffle && index<keys.lastIndex,onClick={connection.moveKey(key,keys[index+1]);menu=false})
                                            DropdownMenuItem(text={Text("Keluarkan dari antrean")},onClick={remove(key);menu=false})
                                        }
                                    }
                                }
                                if(track!=null)TrackRow(track,index==state.index,false,{connection.jump(index)},{},trailing=handle)
                                else ListItem(headlineContent={Text("Lagu tidak tersedia")},trailingContent=handle)
                            }
                        }
                    }
                }
            }
        }
    }
}
