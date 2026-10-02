package com.example.tagcamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
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
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var etTag: AutoCompleteTextView
    private lateinit var tvInfo: TextView
    private lateinit var sp: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etTag = findViewById(R.id.etTag)
        tvInfo = findViewById(R.id.tvInfo)
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

    /** 更新底部提示：当前前缀 + 下一个序号 */
    private fun updateInfo() {
        val tag = currentTag()
        if (tag.isEmpty()) {
            tvInfo.text = "请输入文件名前缀"
            return
        }
        val next = sp.getInt("index_$tag", 0) + 1
        tvInfo.text = "将保存为：${tag}_${String.format("%03d", next)}.jpg"
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

        // 读取并递增序号
        val index = sp.getInt("index_$tag", 0) + 1
        sp.edit().putInt("index_$tag", index).apply()

        val fileName = String.format("%s_%03d.jpg", tag, index)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$tag")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!

        val options = ImageCapture.OutputFileOptions.Builder(resolver, uri).build()
        imageCapture.takePicture(options, executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    runOnUiThread {
                        tvInfo.text = "已保存：DCIM/$tag/$fileName"
                        Toast.makeText(this@MainActivity,
                            "已保存 $fileName", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    resolver.delete(uri)
                    // 保存失败则回退序号
                    sp.edit().putInt("index_$tag", index - 1).apply()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity,
                            "失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            })
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
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "相机启动失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            startCamera()
    }
}