package app.sona.notch

import android.content.Context
import android.view.MotionEvent
import android.view.View

/**
 * Window transparan yang hanya menangkap sentuhan dan meneruskannya ke [target] (window gambar yang
 * tidak bisa disentuh). Ukuran window ini boleh berubah kapan saja karena tidak ada yang digambar di
 * sini, sedangkan window gambar tidak pernah di-resize sehingga tidak ada kedipan buffer lama.
 */
class TouchProxy(
    context: Context,
    private val target: NotchLayout,
    /** Selisih x antara tepi kiri window gambar dan tepi kiri window ini. */
    private val offsetX: () -> Float,
) : View(context) {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            target.onOutside()
            return true
        }
        val copy = MotionEvent.obtain(ev)
        copy.offsetLocation(offsetX(), 0f)
        target.dispatchTouchEvent(copy)
        copy.recycle()
        return true
    }
}
