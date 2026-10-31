package com.example.accounting.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.accounting.R

class ReportFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // يمكن استبدال التخطيط بواجهة مخصصة للتقارير
        return inflater.inflate(R.layout.fragment_list, container, false)
    }
}
