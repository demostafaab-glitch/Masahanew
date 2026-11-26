package com.masahanew.dxfsurvey

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast

/** A point listed in the survey table. Angle and distance are null for points made from coordinates only. */
private data class SurveyPoint(
    val name: String,
    val e: Double,
    val n: Double,
    val angleGon: Double?,
    val distance: Double?
)

class MainActivity : Activity() {

    private lateinit var dxfView: DxfView
    private lateinit var status: TextView
    private lateinit var surveyPanel: ScrollView
    private lateinit var stakeGroup: LinearLayout
    private lateinit var detailGroup: LinearLayout
    private lateinit var resultView: TextView
    private lateinit var pointsTable: TableLayout
    private lateinit var angleField: EditText
    private lateinit var distField: EditText
    private lateinit var stakeStation: PointRow
    private lateinit var stakeZero: PointRow
    private lateinit var stakeTarget: PointRow
    private lateinit var detailStation: PointRow
    private lateinit var detailZero: PointRow

    private val snapButtons = LinkedHashMap<SnapMode, Button>()
    private val surveyPoints = mutableListOf<SurveyPoint>()
    private var pointNumber = 1
    private var model: DxfModel? = null

    private val reqOpen = 1
    private val reqSave = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dxfView = DxfView(this)
        status = TextView(this).apply {
            textSize = 14f
            setPadding(16, 8, 16, 8)
            text = "DXF Survey v${BuildConfig.VERSION_NAME} — تصميم المهندس مصطفى عبدو"
        }
        dxfView.onSelectionChanged = { e ->
            status.text = if (e != null) "محدد: ${e.type}" else "لا يوجد تحديد"
        }
        dxfView.onModelChanged = { status.text = "تم التعديل" }
        dxfView.onPickMiss = { toast("لا توجد نقطة قريبة من النوع المحدد") }

        // Main toolbar
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
        button("مساحة") { toggleSurveyPanel() }
        button("إيقاف الالتقاط") { stopPicking() }
        button("حفظ كنسخة") { saveCopy() }
        button("+") { dxfView.zoomBy(1.25f) }
        button("-") { dxfView.zoomBy(0.8f) }

        // Snap type row
        val snapRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (mode in SnapMode.values()) {
            val b = Button(this)
            b.text = snapLabel(mode)
            b.setOnClickListener { setSnap(mode) }
            snapRow.addView(b)
            snapButtons[mode] = b
        }

        // Survey rows
        stakeStation = PointRow("المحطة", Role.STATION)
        stakeZero = PointRow("التصفير", Role.ZERO)
        stakeTarget = PointRow("الهدف", Role.TARGET)
        detailStation = PointRow("المحطة", Role.STATION)
        detailZero = PointRow("التصفير", Role.ZERO)
        angleField = numField("الزاوية الأفقية (غ)", null)
        distField = numField("المسافة (م)", null)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply { text = "توقيع"; setOnClickListener { showMode(true) } })
            addView(Button(this@MainActivity).apply { text = "مسح تفصيلي"; setOnClickListener { showMode(false) } })
        }

        stakeGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(stakeStation.row)
            addView(stakeZero.row)
            addView(stakeTarget.row)
            addView(Button(this@MainActivity).apply {
                text = "ابدأ التوقيع (محطة ← تصفير ← أهداف)"
                setOnClickListener { startStakeSequence() }
            })
            addView(Button(this@MainActivity).apply {
                text = "إضافة الهدف المدخل يدوياً"
                setOnClickListener { addManualTarget() }
            })
        }

        detailGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(detailStation.row)
            addView(detailZero.row)
            addView(Button(this@MainActivity).apply {
                text = "التقاط المحطة والتصفير"
                setOnClickListener { pickFor(detailStation) { pickFor(detailZero) } }
            })
            addView(angleField)
            addView(distField)
            addView(Button(this@MainActivity).apply {
                text = "احسب وأنشئ النقطة"
                setOnClickListener { createDetailPoint() }
            })
        }

        // Points table
        pointsTable = TableLayout(this).apply {
            addView(TableRow(this@MainActivity).apply {
                addView(cell("الاسم"))
                addView(cell("E"))
                addView(cell("N"))
                addView(cell("الزاوية (غ)"))
                addView(cell("المسافة (م)"))
            })
        }
        val clearButton = Button(this).apply {
            text = "مسح الجدول"
            setOnClickListener { clearTable() }
        }

        resultView = TextView(this).apply {
            textSize = 16f
            setPadding(16, 8, 16, 8)
        }

        val panelContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(modeRow)
            addView(snapRow)
            addView(stakeGroup)
            addView(detailGroup)
            addView(resultView)
            addView(pointsTable)
            addView(clearButton)
        }
        surveyPanel = ScrollView(this).apply {
            addView(panelContent)
            visibility = View.GONE
        }

        val panelHeight = (360 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(this@MainActivity).apply { addView(bar) })
            addView(dxfView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(surveyPanel, LinearLayout.LayoutParams(-1, panelHeight))
            addView(status)
        }
        setContentView(root)
        setSnap(SnapMode.END)
    }

    private fun snapLabel(m: SnapMode): String = when (m) {
        SnapMode.START -> "بداية"
        SnapMode.MIDDLE -> "منتصف"
        SnapMode.END -> "نهاية"
        SnapMode.POINT -> "نقطة"
    }

    private fun setSnap(mode: SnapMode) {
        dxfView.snapMode = mode
        for ((m, b) in snapButtons) b.isEnabled = m != mode
        status.text = "نوع الالتقاط: ${snapLabel(mode)}"
    }

    private fun toggleSurveyPanel() {
        surveyPanel.visibility = if (surveyPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun showMode(stake: Boolean) {
        stakeGroup.visibility = if (stake) View.VISIBLE else View.GONE
        detailGroup.visibility = if (stake) View.GONE else View.VISIBLE
        resultView.text = ""
    }

    private fun stopPicking() {
        dxfView.cancelPick()
        status.text = "تم إيقاف الالتقاط"
    }

    /**
     * Picks a point of [row] with the current snap type. When it is picked, [onDone] runs
     * (used to chain station → zero → target automatically).
     */
    private fun pickFor(row: PointRow, onDone: (Survey.Pt) -> Unit = {}) {
        dxfView.startPick { x, y ->
            val p = Survey.Pt(x, y)
            row.fill(p)
            dxfView.setMarker(row.role, x, y)
            status.text = "تم التقاط ${row.label}: E=${f3(x)}  N=${f3(y)}"
            onDone(p)
        }
        status.text = "اضغط على الشاشة لالتقاط ${row.label} (${snapLabel(dxfView.snapMode)})"
    }

    /** Station, then zero, then targets continuously until "إيقاف الالتقاط". */
    private fun startStakeSequence() {
        pickFor(stakeStation) {
            pickFor(stakeZero) {
                pickFor(stakeTarget, ::onTargetPicked)
            }
        }
    }

    private fun onTargetPicked(p: Survey.Pt) {
        val st = stakeStation.value()
        val z = stakeZero.value()
        if (st != null && z != null) {
            val r = Survey.compute(st, z, p)
            addSurveyPoint(SurveyPoint(nextName(), p.e, p.n, r.horizontalGon, r.distance))
        } else {
            toast("حدّد المحطة والتصفير أولاً")
        }
        pickFor(stakeTarget, ::onTargetPicked)
    }

    private fun addManualTarget() {
        val st = stakeStation.value()
        val z = stakeZero.value()
        val t = stakeTarget.value()
        if (st == null || z == null || t == null) {
            toast("أدخل المحطة والتصفير والهدف")
            return
        }
        val r = Survey.compute(st, z, t)
        addSurveyPoint(SurveyPoint(nextName(), t.e, t.n, r.horizontalGon, r.distance))
        dxfView.setMarker(Role.TARGET, t.e, t.n)
    }

    /** Detail survey: computes the point, draws it in the file, and lists it. */
    private fun createDetailPoint() {
        val st = detailStation.value()
        val z = detailZero.value()
        val ang = parseNum(angleField)
        val d = parseNum(distField)
        if (st == null || z == null || ang == null || d == null) {
            toast("أدخل المحطة والتصفير والزاوية والمسافة")
            return
        }
        val p = Survey.pointFromAngleDistance(st, z, ang, d)
        val name = nextName()

        val m = model
        if (m != null) {
            m.snapshot()
            m.entities.add(pointEntity(p))
            m.entities.add(labelEntity(p, name))
            dxfView.refreshAll()
        } else {
            toast("افتح ملف DXF لرسم النقطة")
        }

        addSurveyPoint(SurveyPoint(name, p.e, p.n, ang, d))
        resultView.text = "تم إنشاء $name\nE = ${f4(p.e)}\nN = ${f4(p.n)}"
    }

    private fun nextName(): String = "P${pointNumber++}"

    private fun addSurveyPoint(sp: SurveyPoint) {
        surveyPoints.add(sp)
        pointsTable.addView(TableRow(this).apply {
            addView(cell(sp.name))
            addView(cell(f3(sp.e)))
            addView(cell(f3(sp.n)))
            addView(cell(sp.angleGon?.let { f4(it) } ?: "-"))
            addView(cell(sp.distance?.let { f3(it) } ?: "-"))
        })
    }

    private fun clearTable() {
        while (pointsTable.childCount > 1) pointsTable.removeViewAt(1)
        surveyPoints.clear()
    }

    private fun cell(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(12, 8, 12, 8)
    }

    private fun pointEntity(p: Survey.Pt): DxfEntity = DxfEntity(
        mutableListOf(
            DxfPair(0, "POINT"),
            DxfPair(8, "0"),
            DxfPair(10, DxfEntity.fmt(p.e)),
            DxfPair(20, DxfEntity.fmt(p.n))
        )
    )

    private fun labelEntity(p: Survey.Pt, name: String): DxfEntity = DxfEntity(
        mutableListOf(
            DxfPair(0, "TEXT"),
            DxfPair(8, "0"),
            DxfPair(10, DxfEntity.fmt(p.e)),
            DxfPair(20, DxfEntity.fmt(p.n)),
            DxfPair(40, "2.0"),
            DxfPair(1, name)
        )
    )

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
            dxfView.cancelPick()
            dxfView.model = m
            val drawn = m.entities.count { it.isSupported() }
            status.text = "الكيانات: ${m.entities.size}، المرسومة: $drawn"
        } catch (e: Exception) {
            toast("خطأ في فتح الملف: ${e.message}")
        }
    }

    /** Writes the edited copy to a new file chosen by the user. The original is never touched. */
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
            dxfView.refreshAll()
            toast("تم التراجع")
        } else {
            toast("لا يوجد ما يُتراجع عنه")
        }
    }

    private fun editText() {
        val m = model
        val e = dxfView.selected
        if (m == null || e == null || (e.type != "TEXT" && e.type != "MTEXT")) {
            toast("حدّد كياناً نصياً أولاً")
            return
        }
        val input = EditText(this).apply { setText(e.getStr(1) ?: "") }
        AlertDialog.Builder(this)
            .setTitle("تعديل النص")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                m.snapshot()
                e.setStr(1, input.text.toString())
                dxfView.refreshEntity(e)
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
                if (x == null || y == null) {
                    toast("قيمة غير صالحة")
                } else {
                    m.snapshot()
                    e.setFirstPoint(x, y)
                    dxfView.refreshEntity(e)
                    status.text = "تم تعديل الإحداثيات"
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun numField(label: String, value: Double?): EditText =
        EditText(this).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(value?.let { DxfEntity.fmt(it) } ?: "")
        }

    private fun parseNum(f: EditText): Double? =
        f.text.toString().trim().replace(',', '.').toDoubleOrNull()

    private fun f4(v: Double): String = "%.4f".format(v)
    private fun f3(v: Double): String = "%.3f".format(v)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** One point: East and North fields, plus a button to pick it from the drawing. */
    private inner class PointRow(val label: String, val role: Role) {
        private val east = numField("$label E", null)
        private val north = numField("$label N", null)

        val row: LinearLayout = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply { text = label; setPadding(8, 0, 8, 0) })
            addView(east, LinearLayout.LayoutParams(0, -2, 1f))
            addView(north, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@MainActivity).apply {
                text = "التقاط"
                setOnClickListener { pickFor(this@PointRow) }
            })
        }

        fun fill(p: Survey.Pt) {
            east.setText(DxfEntity.fmt(p.e))
            north.setText(DxfEntity.fmt(p.n))
        }

        fun value(): Survey.Pt? {
            val x = parseNum(east)
            val y = parseNum(north)
            return if (x == null || y == null) null else Survey.Pt(x, y)
        }
    }
}
