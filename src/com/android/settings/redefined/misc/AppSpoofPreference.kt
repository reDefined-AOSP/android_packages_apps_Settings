package com.android.settings.redefined.misc

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RelativeLayout
import androidx.preference.PreferenceViewHolder
import com.android.settings.R
import com.android.settingslib.widget.AppSwitchPreference

class AppSpoofPreference(context: Context) : AppSwitchPreference(context) {

    var currentMode: TargetMode = TargetMode.AUTO
    var onModeChangeListener: ((TargetMode) -> Unit)? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        
        val titleView = holder.findViewById(android.R.id.title)
        val textFrame = titleView?.parent as? RelativeLayout
        
        var modeGroup = holder.findViewById(R.id.mode_group) as? RadioGroup
        if (modeGroup == null && textFrame != null) {
            val inflater = LayoutInflater.from(context)
            modeGroup = inflater.inflate(R.layout.tricky_store_app_mode_group, textFrame, false) as RadioGroup
            
            val params = modeGroup.layoutParams as RelativeLayout.LayoutParams
            params.addRule(RelativeLayout.BELOW, android.R.id.summary)
            modeGroup.layoutParams = params
            
            textFrame.addView(modeGroup)
        }
        
        // Prevent the switch from dropping to the middle when the card expands
        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as? LinearLayout
        val widgetLp = widgetFrame?.layoutParams as? LinearLayout.LayoutParams
        if (widgetLp != null) {
            widgetLp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            widgetLp.topMargin = 16 // To align perfectly with the title text
            widgetFrame.layoutParams = widgetLp
        }
        
        modeGroup?.visibility = if (isChecked) View.VISIBLE else View.GONE
        
        modeGroup?.setOnCheckedChangeListener(null)
        when (currentMode) {
            TargetMode.AUTO -> modeGroup?.check(R.id.mode_auto)
            TargetMode.LEAF_HACK -> modeGroup?.check(R.id.mode_leaf)
            TargetMode.CERT_GEN -> modeGroup?.check(R.id.mode_cert)
        }
        
        modeGroup?.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.mode_leaf -> TargetMode.LEAF_HACK
                R.id.mode_cert -> TargetMode.CERT_GEN
                else -> TargetMode.AUTO
            }
            if (currentMode != mode) {
                currentMode = mode
                onModeChangeListener?.invoke(mode)
            }
        }
    }
    
    override fun onClick() {
        super.onClick()
        notifyChanged()
    }
}
