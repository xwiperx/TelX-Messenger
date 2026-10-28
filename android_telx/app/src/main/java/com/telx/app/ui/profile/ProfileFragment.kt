package com.telx.app.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.telx.app.R
import com.telx.app.data.Prefs
import com.telx.app.data.optStringOrNull
import com.telx.app.ui.common.AvatarView
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        val avatar = v.findViewById<AvatarView>(R.id.profile_avatar)
        val name = v.findViewById<TextView>(R.id.profile_name)
        val username = v.findViewById<TextView>(R.id.profile_username)
        val bio = v.findViewById<TextView>(R.id.profile_bio)

        avatar.bind(Prefs.myName, Prefs.myAvatar, Helpers.colorFor(Prefs.myId))
        name.text = Prefs.myName.ifEmpty { "TelX user" }
        username.text = "@${Prefs.myUsername}"
        bio.text = ""
        loadBio()

        v.findViewById<View>(R.id.btn_edit_profile).setOnClickListener {
            startActivity(Intent(requireContext(), EditProfileActivity::class.java))
        }
        v.findViewById<View>(R.id.profile_avatar).setOnClickListener {
            startActivity(Intent(requireContext(), EditProfileActivity::class.java))
        }
    }

    private fun loadBio() {
        val ui = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        ui.launch {
            try {
                val j = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.telx.app.net.Api.profileGet()
                }
                val u = j.optJSONObject("user") ?: return@launch
                view?.findViewById<TextView>(R.id.profile_bio)?.text = u.optString("bio", "")
                Prefs.myAvatar = u.optStringOrNull("avatar")
                Prefs.myName = u.optString("name")
                Prefs.myUsername = u.optString("username")
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        val avatar = view?.findViewById<AvatarView>(R.id.profile_avatar) ?: return
        avatar.bind(Prefs.myName, Prefs.myAvatar, Helpers.colorFor(Prefs.myId))
        view?.findViewById<TextView>(R.id.profile_name)?.text = Prefs.myName
        view?.findViewById<TextView>(R.id.profile_username)?.text = "@${Prefs.myUsername}"
    }
}
