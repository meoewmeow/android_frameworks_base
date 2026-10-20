package dev.rime.preview.mock

import android.content.Context
import dev.rime.ui.settings.*
import org.json.JSONObject
import org.json.JSONArray
import dev.rime.ui.controlcenter.ControlLayoutItem

/** App-local persistence. Never writes the stock device's SystemUI/Secure settings. */
class PreviewAppearanceStore(context: Context) {
    private val preferences = context.getSharedPreferences("rime_appearance", Context.MODE_PRIVATE)
    fun read(): LockscreenSettings = runCatching {
        AppearanceCodec.decode(JSONObject(preferences.getString("settings", "{}") ?: "{}"))
    }.getOrDefault(LockscreenSettings())
    fun write(settings: LockscreenSettings) {
        preferences.edit().putString("settings", AppearanceCodec.encode(settings).toString()).apply()
    }
}

internal object AppearanceCodec {
    fun decode(json: JSONObject): LockscreenSettings {
        val defaults = LockscreenSettings(); val c = defaults.clock; val s = defaults.surfaces
        return defaults.copy(controls = if (json.has("controls")) json.getJSONArray("controls").let { array ->
            (0 until array.length()).map { i -> array.getJSONObject(i).let { item -> ControlLayoutItem(item.getString("id"), item.optInt("columns", 1), item.optInt("rows", 1)) } }
        } else defaults.controls, clock = c.copy(
            heightScale = json.optDouble("heightScale", 1.0).toFloat(),
            face = runCatching { ClockFace.valueOf(json.optString("face", "AxionWide")) }.getOrDefault(ClockFace.AxionWide),
            sizeScale = json.optDouble("size", c.sizeScale.toDouble()).toFloat(),
            horizontalOffsetDp = json.optDouble("x", 0.0).toFloat(), topPaddingDp = json.optDouble("y", 0.0).toFloat(),
            dateBelow = json.optBoolean("dateBelow", c.dateBelow), showDate = json.optBoolean("date", c.showDate),
            dualTone = json.optBoolean("dual", c.dualTone),
            colorArgb = if (json.has("color")) if (json.isNull("color")) null else json.getInt("color") else c.colorArgb,
            translucentDigits = json.optBoolean("translucent", c.translucentDigits),
            digitOpacity = json.optDouble("digits", c.digitOpacity.toDouble()).toFloat(),
            blurRadiusDp = if (json.isNull("clockBlur")) null else json.optDouble("clockBlur").toFloat(),
        ), surfaces = s.copy(cornerRadiusDp = json.optDouble("radius", s.cornerRadiusDp.toDouble()).toFloat(),
            blurRadiusDp = json.optDouble("blur", s.blurRadiusDp.toDouble()).toFloat(),
            opacity = json.optDouble("opacity", s.opacity.toDouble()).toFloat())).validated()
    }
    fun encode(settings: LockscreenSettings): JSONObject {
        val c = settings.clock; val s = settings.surfaces
        val json = JSONObject().put("heightScale", c.heightScale).put("face", c.face.name).put("size", c.sizeScale).put("x", c.horizontalOffsetDp).put("y", c.topPaddingDp)
            .put("dateBelow", c.dateBelow).put("date", c.showDate).put("dual", c.dualTone)
            .put("color", c.colorArgb ?: JSONObject.NULL).put("translucent", c.translucentDigits).put("digits", c.digitOpacity)
            .put("clockBlur", c.blurRadiusDp ?: JSONObject.NULL)
            .put("radius", s.cornerRadiusDp).put("blur", s.blurRadiusDp).put("opacity", s.opacity)
        json.put("controls", JSONArray().apply { settings.controls.forEach { item ->
            put(JSONObject().put("id", item.id).put("columns", item.columns).put("rows", item.rows))
        } })
        return json
    }
}
