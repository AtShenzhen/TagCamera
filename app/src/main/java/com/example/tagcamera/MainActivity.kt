package com.example.tagcamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var imageCapture: ImageCapture
    private lateinit var videoCapture: VideoCapture
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var etTag: AutoCompleteTextView
    private lateinit var tvInfo: TextView
    private lateinit var btnRec: Button
    private lateinit var sp: android.content.SharedPreferences
    private var activeRecording: Recording? = null
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etTag = findViewById(R.id.etTag)
        tvInfo = findViewById(R.id.tvInfo)
        btnRec = findViewById(R.id.btnRec)
        sp = getSharedPreferences("config", MODE_PRIVATE)

        // 恢复上次前缀 + 历史记录（按最近使用倒序）
        val history = loadHistory()
        etTag.setText(sp.getString("lastTag", ""))
        etTag.setAdapter(ArrayAdapter(this,
            android.R.layout.simple_list_item_1, history))

        etTag.setOnItemClickListener { _, _, pos, _ ->
            etTag.setText((etTag.adapter as ArrayAdapter<String>).getItem(pos))
            saveTag()
        }
        etTag.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { saveTag(); true } else false
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        } else startCamera()

        findViewById<Button>(R.id.btnShot).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.btnPhoto).setOnClickListener { takePhoto() }
        btnRec.setOnClickListener { toggleRecording() }
        updateInfo()
    }

    /** 保存当前前缀（写入历史与上次前缀）并更新界面提示 */
    private fun saveTag() {
        val tag = currentTag()
        if (tag.isEmpty()) return
        sp.edit().putString("lastTag", tag).apply()
        val list = loadHistory().toMutableList()
        list.remove(tag)        // 去重
        list.add(0, tag)        // 最近使用的排在最前
        val trimmed = list.take(10)
        saveHistoryList(trimmed)
        etTag.setAdapter(ArrayAdapter(this,
            android.R.layout.simple_list_item_1, trimmed))
        updateInfo()
    }

    /** 读取历史前缀（最近使用倒序，最多 10 条），兼容旧版 StringSet 存储 */
    private fun loadHistory(): List<String> {
        val raw = sp.getString("history_list", null)
        if (raw != null) {
            val out = mutableListOf<String>()
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            return out
        }
        // 旧版 StringSet 迁移
        val old = sp.getStringSet("history", emptySet())!!.toList()
        if (old.isNotEmpty()) saveHistoryList(old)
        return old
    }

    /** 以 JSON 数组持久化历史（保留顺序） */
    private fun saveHistoryList(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp.edit().putString("history_list", arr.toString()).apply()
    }

    /** 更新底部提示：当前前缀 + 下一个序号（照片/视频共用） */
    private fun updateInfo() {
        val tag = currentTag()
        if (tag.isEmpty()) {
            tvInfo.text = "请输入文件名前缀"
            return
        }
        val next = sp.getInt("index_$tag", 0) + 1
        tvInfo.text = "将保存为：${tag}_${String.format("%03d", next)}.(jpg/mp4)"
    }

    private fun currentTag(): String =
        etTag.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun takePhoto() {
        val tag = currentTag()
        if (tag.isEmpty()) {
            Toast.makeText(this, "请先输入文件名前缀", Toast.LENGTH_SHORT).show()
            return
        }
        saveTag()   // 拍照即把当前前缀写入历史与上次前缀

        // 读取并递增序号（与录像共用 index_$tag）
        val index = sp.getInt("index_$tag", 0) + 1
        sp.edit().putInt("index_$tag", index).apply()

        val fileName = String.format("%s_%03d.jpg", tag, index)

        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$tag")
        }

        // 标准 cameraX MediaStore 用法：第二个参数传 MediaStore 集合 URI（而非已 insert 的
        // 具体条目 uri），由 cameraX 自行 insert 并写入；原写法对具体条目 uri 再 insert 会失败
        val options = ImageCapture.OutputFileOptions.Builder(
            resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        imageCapture.takePicture(options, executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    runOnUiThread {
                        tvInfo.text = "已保存：DCIM/$tag/$fileName"
                        Toast.makeText(this@MainActivity,
                            "已保存 $fileName", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    // 保存失败则回退序号
                    sp.edit().putInt("index_$tag", index - 1).apply()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity,
                            "失败[${e.imageCaptureError}]：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            })
    }

    /** 录像按钮：未录制则开始，录制中则停止 */
    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
            return
        }
        // 带声音需在运行时请求 RECORD_AUDIO 权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        if (!::videoCapture.isInitialized) {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val tag = currentTag()
        if (tag.isEmpty()) {
            Toast.makeText(this, "请先输入文件名前缀", Toast.LENGTH_SHORT).show()
            return
        }
        saveTag()   // 录制同样把前缀写入历史

        // 与拍照共用 index_$tag，靠扩展名 .mp4 区分
        val index = sp.getInt("index_$tag", 0) + 1
        sp.edit().putInt("index_$tag", index).apply()

        val fileName = String.format("%s_%03d.mp4", tag, index)
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/$tag")
        }
        val options = MediaStoreOutputOptions.Builder(
            resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()

        try {
            activeRecording = videoCapture.output
                .prepareRecording(this, options)
                .withAudioEnabled()   // 需 RECORD_AUDIO 权限
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                            updateRecUI()
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecording = false
                            if (event.hasError()) {
                                // 失败回退序号
                                sp.edit().putInt("index_$tag", index - 1).apply()
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity,
                                        "录像失败：${event.error}", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                runOnUiThread {
                                    tvInfo.text = "已保存：DCIM/$tag/$fileName"
                                    Toast.makeText(this@MainActivity,
                                        "已保存 $fileName", Toast.LENGTH_SHORT).show()
                                }
                            }
                            updateRecUI()
                        }
                        else -> {}
                    }
                }
        } catch (e: Exception) {
            sp.edit().putInt("index_$tag", index - 1).apply()
            isRecording = false
            updateRecUI()
            Toast.makeText(this, "录像启动失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    /** 切换录像按钮外观：录像 / 停止（变红） */
    private fun updateRecUI() {
        btnRec.text = if (isRecording) "停止" else "录像"
        btnRec.setBackgroundColor(
            if (isRecording) Color.RED else Color.parseColor("#3F51B5"))
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.preview).surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            videoCapture = VideoCapture.Builder().build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture, videoCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "相机启动失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
            2 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
        }
    }
}
