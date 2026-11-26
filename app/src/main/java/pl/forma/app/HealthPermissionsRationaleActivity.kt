package pl.forma.app

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Privacy rationale that Health Connect opens ("Zobacz, jak ta aplikacja używa danych") and Android 14+ opens through the
 * VIEW_PERMISSION_USAGE alias. A plain native screen (no WebView, no layout file): short Polish text and two buttons.
 */
class HealthPermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad * 2, pad, pad)
        root.setBackgroundColor(Color.parseColor("#0B0E11"))

        val title = TextView(this)
        title.text = "Dane zdrowotne w Formie"
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        root.addView(title)

        val body = TextView(this)
        body.text = "Forma korzysta z Health Connect, żeby:\n\n" +
            "• odczytać z innych aplikacji kroki, wagę, wzrost, treningi, sen, tętno, tętno spoczynkowe, HRV oraz spalone kalorie,\n" +
            "• zapisać tam Twoje pomiary: HRV, tętno spoczynkowe, treningi z tętnem i sen.\n\n" +
            "Dane zostają na Twoim telefonie. Forma nie wysyła ich na żaden serwer, nikomu ich nie udostępnia i nie używa do reklam. " +
            "Zapisujemy tylko rekordy, które sama utworzyła; dane pochodzące z innych aplikacji nie wracają do Health Connect.\n\n" +
            "Każde uprawnienie możesz w każdej chwili zmienić lub cofnąć w ustawieniach Health Connect."
        body.textSize = 16f
        body.setTextColor(Color.parseColor("#C5CED6"))
        body.setLineSpacing(0f, 1.25f)
        body.setPadding(0, pad, 0, pad)
        val scroll = ScrollView(this)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val settings = Button(this)
        settings.text = "Ustawienia Health Connect"
        settings.setOnClickListener { HealthConnectManager.openSettings(this) }
        root.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val close = Button(this)
        close.text = "Zamknij"
        close.gravity = Gravity.CENTER
        close.setOnClickListener { finish() }
        root.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }
}
