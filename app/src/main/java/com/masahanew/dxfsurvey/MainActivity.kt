package com.masahanew.dxfsurvey

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var dxfView: DxfView
    private lateinit var status: TextView
    private var model: DxfModel? = null
    private val reqOpen = 1
    private val reqSave = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dxfView = DxfView(this)
        status = TextView(this).apply {
            textSize = 14f
            setPadding(16, 8, 16, 8)
            text = "افتح ملف DXF للبدء"
        }
        dxfView.onSelectionChanged = { e ->
            status.text = if (e != null) "محدد: ${e.type}" else "لا يوجد تحديد"
        }
        dxfView.onModelChanged = { status.text = "تم التعديل" }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun button(label: String, onClick: () -> Unit) {
            bar.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }
        button("فتح") { openFile() }
        button("تراجع") { undo() }
        button("نص") { editText() }
        button("إحداثيات") { editCoords() }
        button("مساحة") { surveyDialog() }
        button("حفظ كنسخة") { saveCopy() }
        button("+") { dxfView.zoomBy(1.25f) }
        button("-") { dxfView.zoomBy(0.8f) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(this@MainActivity).apply { addView(bar) })
            addView(dxfView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(status)
        }
        setContentView(root)
    }

    private fun openFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(intent, reqOpen)
    }

    private fun saveCopy() {
        if (model == null) {
            toast("افتح ملفاً أولاً")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, "edited.dxf")
        startActivityForResult(intent, reqSave)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            reqOpen -> loadFile(uri)
            reqSave -> writeFile(uri)
        }
    }

    private fun loadFile(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("تعذّر قراءة الملف")
            val m = DxfModel.parse(text)
            model = m
            dxfView.model = m
            val supported = m.entities.count { it.isSupported() }
            status.text = "الكيانات: ${m.entities.size}، المدعومة للتعديل: $supported"
        } catch (e: Exception) {
            toast("خطأ في فتح الملف: ${e.message}")
        }
    }

    private fun writeFile(uri: Uri) {
        val m = model ?: return
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(m.serialize().toByteArray(Charsets.UTF_8))
            }
            toast("تم الحفظ كملف جديد")
        } catch (e: Exception) {
            toast("خطأ في الحفظ: ${e.message}")
        }
    }

    private fun undo() {
        val m = model ?: return
        if (m.undo()) {
            dxfView.select(null)
            dxfView.invalidate()
            toast("تم التراجع")
        } else toast("لا يوجد ما يُتراجع عنه")
    }

    private fun editText() {
        val m = model
        val e = dxfView.selected
        if (m == null || e == null || e.type != "TEXT") {
            toast("حدّد كياناً من نوع TEXT أولاً")
            return
        }
        val input = EditText(this).apply { setText(e.getStr(1) ?: "") }
        AlertDialog.Builder(this)
            .setTitle("تعديل النص")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                m.snapshot()
                e.setStr(1, input.text.toString())
                dxfView.invalidate()
                status.text = "تم تعديل النص"
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun editCoords() {
        val m = model
        val e = dxfView.selected
        if (m == null || e == null) {
            toast("حدّد كياناً أولاً")
            return
        }
        val pt = e.vertices().firstOrNull()
        val xIn = numField("X (شرق)", pt?.get(0))
        val yIn = numField("Y (شمال)", pt?.get(1))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(xIn)
            addView(yIn)
        }
        AlertDialog.Builder(this)
            .setTitle("تعديل الإحداثيات (النقطة الأولى)")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val x = parseNum(xIn)
                val y = parseNum(yIn)
                if (x == null || y == null) toast("قيمة غير صالحة")
                else {
                    m.snapshot()
                    e.setFirstPoint(x, y)
                    dxfView.invalidate()
                    status.text = "تم تعديل الإحداثيات"
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun surveyDialog() {
        val st = dxfView.selected?.vertices()?.firstOrNull()
        val fields = listOf(
            numField("محطة E", st?.get(0)), numField("محطة N", st?.get(1)),
            numField("تصفير E", null), numField("تصفير N", null),
            numField("هدف E", null), numField("هدف N", null)
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            fields.forEach { addView(it) }
        }
        AlertDialog.Builder(this)
            .setTitle("حساب مساحي (غراد، متر)")
            .setView(box)
            .setPositiveButton("احسب") { _, _ ->
                val v = fields.map { parseNum(it) }
                if (v.any { it == null }) {
                    toast("أدخل جميع القيم")
                    return@setPositiveButton
                }
                val r = Survey.compute(
                    Survey.Pt(v[0]!!, v[1]!!),
                    Survey.Pt(v[2]!!, v[3]!!),
                    Survey.Pt(v[4]!!, v[5]!!)
                )
                AlertDialog.Builder(this)
                    .setTitle("النتيجة")
                    .setMessage(
                        "اتجاه التصفير: ${"%.4f".format(r.backDirGon)} غ\n" +
                        "اتجاه الهدف: ${"%.4f".format(r.targetDirGon)} غ\n" +
                        "الزاوية الأفقية: ${"%.4f".format(r.horizontalGon)} غ\n" +
                        "المسافة: ${"%.3f".format(r.distance)} م"
                    )
                    .setPositiveButton("حسناً", null)
                    .show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun numField(label: String, value: Double?): EditText =
        EditText(this).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(value?.let { DxfEntity.fmt(it) } ?: "")
        }

    private fun parseNum(f: EditText): Double? =
        f.text.toString().trim().replace(',', '.').toDoubleOrNull()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
