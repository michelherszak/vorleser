package de.vorleser

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ColumnScope.PhotoReview(photo: File?, page: Int, onRetake: () -> Unit, onRead: () -> Unit, onBack: () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, photo?.path) {
        value = withContext(Dispatchers.IO) {
            photo?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }
        }
    }
    Text(stringResource(R.string.review_page, page), fontSize = 30.sp)
    Text(stringResource(R.string.review_hint), fontSize = 22.sp)
    Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.photo_description), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Text(stringResource(R.string.preview_loading), fontSize = 22.sp)
    }
    Text(stringResource(R.string.upload_notice), fontSize = 18.sp, lineHeight = 24.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton(stringResource(R.string.retake), onRetake, Modifier.weight(1f))
        BigButton(stringResource(R.string.read_page), onRead, Modifier.weight(1f), enabled = bitmap != null)
    }
    TextButton(onClick = onBack) { Text(stringResource(R.string.back), fontSize = 20.sp) }
}
