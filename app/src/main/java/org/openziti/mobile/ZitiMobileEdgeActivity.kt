/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Point
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.Vibrator
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import kotlinx.android.synthetic.main.about.*
import kotlinx.android.synthetic.main.advanced.*
import kotlinx.android.synthetic.main.configuration.*
import kotlinx.android.synthetic.main.dashboard.*
import kotlinx.android.synthetic.main.identities.*
import kotlinx.android.synthetic.main.identity.*
import kotlinx.android.synthetic.main.identityitem.view.*
import kotlinx.android.synthetic.main.log.*
import kotlinx.android.synthetic.main.logs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.openziti.ZitiContext
import org.openziti.android.Ziti
import java.util.*


fun Int.toDp(): Int = (this / Resources.getSystem().displayMetrics.density).toInt()
fun Int.toPx(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()

class ZitiMobileEdgeActivity : AppCompatActivity() {

    lateinit var prefs: SharedPreferences
    val systemId: Int by lazy {
        this.packageManager?.getApplicationInfo("android", PackageManager.GET_META_DATA)?.uid ?: 0
    }
    var isMenuOpen = false

    var ipAddress = "169.254.0.1"
    var subnet = "255.255.255.0"
    var mtu = "4000"
    var dns = "169.254.0.2"
    var state = "startActivity"
    var log_application = ""
    var log_tunneler = ""
    val version = "${BuildConfig.VERSION_NAME}(${BuildConfig.GIT_COMMIT})"
    var startTime = Date()

    lateinit var contextViewModel: ZitiViewModel
    internal var vpn: ZitiVPNService.ZitiVPNBinder? = null
    internal val serviceConnection = object: ServiceConnection{
        override fun onServiceDisconnected(name: ComponentName?) {
            vpn = null
            updateTunnelState()
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vpn = service as ZitiVPNService.ZitiVPNBinder
            updateTunnelState()
        }
    }

    fun launchUrl(url:String) {
        val openURL = Intent(android.content.Intent.ACTION_VIEW)
        openURL.data = Uri.parse(url)
        startActivity(openURL)
    }

    var duration = 300
    var offScreenX = 0
    var offScreenY = 0
    var openY = 0
    var isOpen = false

    fun getScreenWidth(): Int {
        return Resources.getSystem().displayMetrics.widthPixels
    }

    fun getScreenHeight(): Int {
        return Resources.getSystem().displayMetrics.heightPixels
    }

    private fun toggleMenu() {
        var posTo = getScreenWidth()-(getScreenWidth()/3)
        var animatorSet = AnimatorSet()
        var scaleY = ObjectAnimator.ofFloat(MainArea, "scaleY", .9f, 1.0f).setDuration(duration.toLong())
        var scaleX = ObjectAnimator.ofFloat(MainArea, "scaleX", .9f, 1.0f).setDuration(duration.toLong())
        var fader = ObjectAnimator.ofFloat(FrameArea, "alpha", 1f, 0f).setDuration(duration.toLong())

        var animateTo = ObjectAnimator.ofFloat( MainArea,"translationX",posTo.toFloat(), 0f ).setDuration(duration.toLong())
        fader.interpolator = DecelerateInterpolator()
        animateTo.interpolator = DecelerateInterpolator()
        scaleY.interpolator = DecelerateInterpolator()
        scaleX.interpolator = DecelerateInterpolator()
        MainMenu.visibility = View.GONE
        state = "startActivity"
        if (!isMenuOpen) {
            state = "menu"
            MainMenu.visibility = View.VISIBLE
            animateTo = ObjectAnimator.ofFloat(MainArea, "translationX", 0f, posTo.toFloat()).setDuration(duration.toLong())
            scaleY = ObjectAnimator.ofFloat(MainArea, "scaleY", 1.0f, 0.9f).setDuration(duration.toLong())
            scaleX = ObjectAnimator.ofFloat(MainArea, "scaleX", 1.0f, 0.9f).setDuration(duration.toLong())
            fader = ObjectAnimator.ofFloat(FrameArea, "alpha", 0f, 1f).setDuration(duration.toLong())
        }
        animatorSet.play( animateTo ).with(scaleX).with(scaleY).with(fader)
        animatorSet.start()
        isMenuOpen = !isMenuOpen
    }

    private fun toggleSlide(view:View, newState:String) {
        try {
            val inputManager:InputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputManager.hideSoftInputFromWindow(currentFocus!!.windowToken, InputMethodManager.SHOW_FORCED)
        } catch (e:Exception) {}
        var fader = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f).setDuration(duration.toLong())
        var animateTo = ObjectAnimator.ofFloat( view,"translationX", offScreenX.toFloat(), 0f ).setDuration(duration.toLong())
        fader.interpolator = DecelerateInterpolator()
        animateTo.interpolator = DecelerateInterpolator()
        state = newState
        if (view.x==0f) {
            fader = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f).setDuration(duration.toLong())
            animateTo = ObjectAnimator.ofFloat( view,"translationX", 0f, offScreenX.toFloat() ).setDuration(duration.toLong())
        }
        var animatorSet = AnimatorSet()
        animatorSet.play( animateTo ).with(fader)
        animatorSet.start()
    }

    override fun onBackPressed() {
        if (state=="menu") toggleMenu()
        else if (state=="about") toggleSlide(AboutPage, "menu")
        else if (state=="advanced") toggleSlide(AdvancedPage, "menu")
        else if (state=="config") toggleSlide(ConfigPage, "advanced")
        else if (state=="identity") toggleSlide(ConfigPage, "identities")
        else super.onBackPressed()
    }

    private var startPosition = 0f
    private var startSize = 0
    private var openSize = 0
    private var cardWidths = 0


    fun slideView(view:View, currentHeight:Int, newHeight:Int) {

        Log.i("ek-tag", "from ${currentHeight} to ${newHeight}")
        var slideAnimator = ValueAnimator.ofInt(currentHeight, newHeight).setDuration(500)

        slideAnimator.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
            override fun onAnimationUpdate(animation: ValueAnimator) {
                var animatedValue = animation.animatedValue as Int
                view.layoutParams.height = animatedValue
                view.requestLayout()
            }
        })

        var animationSet = AnimatorSet()
        animationSet.interpolator = DecelerateInterpolator()
        animationSet.play(slideAnimator)
        animationSet.start()
    }

    private var closeCardTo = 0
/*
    private fun toggleCardDetails() {
        if (this.isOpen) {
            slideView(IdentityArea, this.openSize, this.startSize)
            MainScroller.scrollable = true
            //MainScroller.smoothScrollTo(closeCardTo, 0)
        } else {
            if (this.startSize==0) {
                this.startSize = IdentityArea.layoutParams.height
                this.openSize = MainAreaLayout.height
            }
            slideView(IdentityArea, this.startSize, this.openSize)
            for (i in IdentityCards.childCount downTo 1) {
                var index = i-1
                var card = IdentityCards.getChildAt(index) as CardView
                if (card.isOpen) {
                    MainScroller.smoothScrollTo(IdentityCards.getChildAt(index).left, 0)
                    closeCardTo = IdentityCards.getChildAt(index).left+150
                    break
                }
            }
            MainScroller.scrollable = false
        }
        this.isOpen = !this.isOpen
    }
*/
    fun difference(): String {
        var stopTime = Date()
        val diff = stopTime.time-startTime.time
        var seconds = diff / 1000
        var minutes = seconds / 60
        var hours = minutes / 60

        if (minutes>0) {
            seconds = seconds/(minutes+1)
        }
        if (hours>0) {
            minutes = minutes/(hours+1)
        }

        var totalString = seconds.toString()
        if (seconds<10) totalString = "0"+seconds
        if (minutes>0) {
            if (minutes>=10 ) totalString = minutes.toString()+":"+totalString
            else totalString = "0"+minutes.toString()+":"+totalString
        } else {
            totalString = "00:"+totalString
        }
        if (hours>0) {
            if (hours>=10) totalString = hours.toString()+":"+totalString
            else totalString = "0"+hours.toString()+":"+totalString
        } else {
            totalString = "00:"+totalString
        }

        return totalString
    }

    fun TurnOff() {
        OnButton.visibility = View.GONE
        OffButton.visibility = View.VISIBLE
        LabelArea.visibility = View.INVISIBLE
        CountArea.visibility = View.INVISIBLE
        CountLabelArea.visibility = View.INVISIBLE
        TimeConnected.visibility = View.INVISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        this.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)


        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        offScreenX = getScreenWidth()+50
        offScreenY = getScreenHeight()-370
        Version.text = "Version: $version"

        // Setup Screens
        AboutPage.visibility = View.VISIBLE
        AdvancedPage.visibility = View.VISIBLE
        ConfigPage.visibility = View.VISIBLE
        LogsPage.visibility = View.VISIBLE
        LogPage.visibility = View.VISIBLE
        IdentityDetailsPage.visibility = View.VISIBLE
        IdentityPage.visibility = View.VISIBLE
        AboutPage.alpha = 0f
        AdvancedPage.alpha = 0f
        ConfigPage.alpha = 0f
        LogsPage.alpha = 0f
        LogPage.alpha = 0f
        IdentityPage.alpha = 0f
        IdentityDetailsPage.alpha = 0f
        AboutPage.x = offScreenX.toFloat()
        AdvancedPage.x = offScreenX.toFloat()
        ConfigPage.x = offScreenX.toFloat()
        LogsPage.x = offScreenX.toFloat()
        LogPage.x = offScreenX.toFloat()
        IdentityPage.x = offScreenX.toFloat()
        IdentityDetailsPage.x = offScreenX.toFloat()
        openY = offScreenY
        this.startPosition = getScreenHeight().toDp()-130.toDp().toFloat()

        //this.startPosition = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, yLoc, getResources().getDisplayMetrics())
        //IdentityArea.y = 10.toDp().toFloat() //this.startPosition

        IPInput.text = ipAddress
        SubNetInput.text = subnet
        MTUInput.text = mtu
        DNSInput.text = dns

        // Dashboard Button Actions
        OffButton.setOnClickListener {
            val vb = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vb.hasVibrator()) vb.vibrate(100)
            val intent = VpnService.prepare(applicationContext)
            if (intent != null) {
                startActivityForResult(intent, 10169)
            } else {
                onActivityResult(10169, AppCompatActivity.RESULT_OK, null)
            }
            startTime = Date()
            OnButton.visibility = View.VISIBLE
            OffButton.visibility = View.GONE
            LabelArea.visibility = View.VISIBLE
            CountArea.visibility = View.VISIBLE
            CountLabelArea.visibility = View.VISIBLE
            TimeConnected.visibility = View.VISIBLE
        }
        OnButton.setOnClickListener {
            val vb = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vb.hasVibrator()) vb.vibrate(100)
            onActivityResult(10168, AppCompatActivity.RESULT_OK, null)
            TurnOff()
        }

        val timer = Timer()
        val task = object: TimerTask() {
            override fun run() {
                TimeConnected.post {
                    TimeConnected.text = difference()
                }
            }
        }
        timer.schedule(task, 0, 1000)

        // Menu Button Actions
        DashboardButton.setOnClickListener {
            toggleMenu()
        }
        MainLogo.setOnClickListener {
            toggleMenu()
        }
        AboutButton.setOnClickListener {
            toggleSlide(AboutPage, "about")
        }
        AdvancedButton.setOnClickListener {
            toggleSlide(AdvancedPage, "advanced")
        }
        FeedbackButton.setOnClickListener {
            startActivity(Intent.createChooser(Ziti.sendFeedbackIntent(), "Send Email"))
        }
        SupportButton.setOnClickListener {
            launchUrl("https://support.netfoundry.io")
        }
        AddIdentityButton.setOnClickListener {
            startActivity(Ziti.getEnrollmentIntent())
        }
        AddIdentityLabel.setOnClickListener {
            startActivity(Ziti.getEnrollmentIntent())
        }
        HamburgerButton.setOnClickListener {
            toggleMenu()
        }
        HamburgerLabel.setOnClickListener {
            toggleMenu()
        }

        // About Button Actions
        PrivacyButton.setOnClickListener {
            launchUrl("https://netfoundry.io/privacy-policy/")
        }
        TermsButton.setOnClickListener {
            launchUrl("https://netfoundry.io/terms/")
        }
        ThirdButton.setOnClickListener {
            launchUrl("https://netfoundry.io/third-party")
        }

        // Back Buttons
        BackButton.setOnClickListener {
            toggleSlide(AboutPage, "menu")
        }
        BackIdentityButton.setOnClickListener {
            toggleSlide(IdentityPage, "menu")
        }
        BackAdvancedButton.setOnClickListener {
            toggleSlide(AdvancedPage, "menu")
        }
        BackConfigButton.setOnClickListener {
            toggleSlide(ConfigPage, "advanced")
        }
        BackConfigButton2.setOnClickListener {
            toggleSlide(ConfigPage, "advanced")
        }
        BackLogsButton.setOnClickListener {
            toggleSlide(ConfigPage, "advanced")
        }
        BackToLogsButton.setOnClickListener {
            toggleSlide(LogPage, "logs")
        }
        BackIdentityDetailsButton.setOnClickListener {
            toggleSlide(IdentityDetailsPage, "identities")
        }
        BackToLogsButton2.setOnClickListener {
            toggleSlide(LogPage, "logs")
        }
        BackLogsButton.setOnClickListener {
            toggleSlide(LogsPage, "advanced")
        }
        CopyLogButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Logs", LogDetails.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,"Log has been copied to your clipboard",Toast.LENGTH_LONG).show()
        }

        // Advanced Buttons
        TunnelButton.setOnClickListener {
            toggleSlide(ConfigPage, "config")
        }
        LogsButton.setOnClickListener {
            toggleSlide(LogsPage, "logs")
        }
        PacketLogsButton.setOnClickListener {
            LogTypeTitle.text = ("Packet Tunnel Logs")
            LogDetails.text = log_tunneler
            toggleSlide(LogPage, "logdetails")
        }

        // Dashboard Buttons
        IdentityButton.setOnClickListener {
            toggleSlide(IdentityPage, "identities")
        }
        IdentityCount.setOnClickListener {
            toggleSlide(IdentityPage, "identities")
        }

        LogDetails.movementMethod = ScrollingMovementMethod()
        ApplicationLogsButton.setOnClickListener {
            LogTypeTitle.text = ("Application Logs")
            LogDetails.text = log_application
            GlobalScope.async(Dispatchers.IO) {
                val p = Runtime.getRuntime().exec("logcat -d -t 200 --pid=${Process.myPid()}")
                val lines = p.inputStream.bufferedReader().readText()

                Log.d("ziti", "log is ${lines.length} bytes")

                LogDetails.post {
                    LogDetails.text = lines
                }
            }
            toggleSlide(LogPage, "logdetails")
        }

        contextViewModel = ViewModelProvider(this).get(ZitiViewModel::class.java)
        contextViewModel.contexts().observe(this, Observer { contextList ->
            //IdentityCards.removeAllViews()
            IdentityList.removeAllViews()
            // create, remove cards
            var index = 0
            for (ctx in contextList) {
                val ctxModel = ViewModelProvider(this, ZitiContextModel.Factory(ctx)).get(ctx.name(), ZitiContextModel::class.java)
                val identityitem = IdentityItemView(this, ctxModel)
                ctxModel.services().observe(this, Observer { serviceList ->
                    identityitem.count = serviceList.count()
                })
                if (ctx.getStatus() == ZitiContext.Status.Active) {
                    identityitem.isOn = true
                }
                identityitem.IdToggleSwitch.setOnCheckedChangeListener { button: CompoundButton, state: Boolean ->
                    ctx.setEnabled(state)
                }

                identityitem.setOnClickListener {
                    toggleSlide(IdentityDetailsPage, "identity")
                    IdIdentityDetailName.text = ctxModel.name()
                    IdDetailsEnrollment.text = ctxModel.status().value?.toString()
                    if (ctx.getStatus() == ZitiContext.Status.Active) {
                        IdOnOffSwitch.isChecked = true
                    }
                    IdOnOffSwitch.setOnCheckedChangeListener { button: CompoundButton, state: Boolean ->
                        ctx.setEnabled(state)
                        true
                    }
                    ctxModel.status().observe(this, Observer { st ->
                        IdDetailsStatus.text = st.toString()
                    })
                    IdDetailsNetwork.text = ctx.controller()
                    IdDetailsNetwork.setOnClickListener {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Network", IdDetailsNetwork.text.toString())
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(applicationContext,IdDetailsNetwork.text.toString() + " has been copied to your clipboard",Toast.LENGTH_LONG).show()
                    }
                    var sCount = 0
                    ctxModel.services().observe(this, Observer { serviceList ->
                        IdDetailServicesList.removeAllViews()
                        for (service in serviceList) {
                            sCount++
                            var line = LineView(applicationContext)
                            line.label = service.name
                            line.value = service.dns?.let { "${it.hostname}:${it.port}" } ?: ""
                            IdDetailServicesList.addView(line)
                        }
                    })
                    IdDetailForgetButton.setOnClickListener {

                        val builder = AlertDialog.Builder(this)
                        builder.setTitle("Confirm")
                        builder.setMessage("Are you sure you want to delete this identity from your device?")
                        builder.setIcon(android.R.drawable.ic_dialog_alert)

                        builder.setPositiveButton("Yes"){_, _ ->
                            ctxModel.delete()
                            Toast.makeText(applicationContext, ctx.name() + " removed", Toast.LENGTH_LONG).show()
                            toggleSlide(IdentityDetailsPage, "identities")
                        }

                        builder.setNeutralButton("Cancel"){_ , _ -> }

                        val alertDialog: AlertDialog = builder.create()
                        alertDialog.setCancelable(false)
                        alertDialog.show()
                    }
                }
                IdentityList.addView(identityitem)
                index++

/*
                val cardView = CardView(this, ctx)
                cardView.name = ctx.name()
                cardView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.FILL_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                cardView.enrollment = ctx.status.name
                ctx.statusLive.observe(this, Observer { st ->
                    cardView.status = st.toString()
                })
                this.cardWidths = getScreenWidth()
                cardView.layoutParams.width = this.cardWidths
                cardView.onToggle = {
                    this.toggleCardDetails()
                }
                if (index==0) {
                    var params = cardView.layoutParams as FrameLayout.LayoutParams
                    params.setMargins(100,0,15,-30)
                    cardView.layoutParams = params
                }
                index++
                if (index==contextList.size) {
                    var params = cardView.layoutParams as FrameLayout.LayoutParams
                    params.setMargins(15,0,100,-30)
                    cardView.layoutParams = params
                }
                Log.i( "ek-tag", "${IdentityCards.childCount} count ${getScreenWidth()} ${getScreenWidth().toDp()} ${cardView.width} ${cardWidths} ${40.toDp()}")
                ctx.servicesLiveData.observe(this, Observer { serviceList ->
                    cardView.services = serviceList
                })
                IdentityCards.addView(cardView)

                Log.i("ek-tag", "${ctx.name()} - ${ctx.status}")
                */

            }
            IdentityCount.text = index.toString()
            if (index==0) {
                if (OffButton!=null) {
                    TurnOff()
                    //OffButton.getBackground().setAlpha(45)
                    OffButton.isClickable = false
                    StateButton.setAlpha(144)
                }
            } else {
                if (OffButton!=null) {
                    //OffButton.getBackground().setAlpha(100)
                    OffButton.isClickable = true
                    StateButton.setAlpha(255)
                }
            }
        })

        contextViewModel.stats().observe(this, Observer {
            setSpeed(it.downRate, DownloadSpeed, DownloadMbps)
            setSpeed(it.upRate, UploadSpeed, UploadMbps)
        })

        prefs = getSharedPreferences("ziti-vpn", Context.MODE_PRIVATE)
        //checkAppList()

        //bindService(Intent(applicationContext, ZitiVPNService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        super.onPause()
        val active = vpn?.isVPNActive() ?: false
        if (!active) {
            // Ziti.pause()
        }
        unbindService(serviceConnection)
    }

    override fun onResume() {
        super.onResume()
        // Ziti.resume()
        bindService(Intent(applicationContext, ZitiVPNService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        updateTunnelState()
    }

    private fun updateTunnelState() {
        val on = vpn?.isVPNActive() ?: false
        updateConnectedView(on)
    }

    private fun updateConnectedView(on: Boolean) {
        OnButton.visibility = if (on) View.VISIBLE else View.GONE
        OffButton.visibility = if (on) View.GONE else View.VISIBLE
        LabelArea.visibility = if (on) View.VISIBLE else View.INVISIBLE
        CountArea.visibility = if (on) View.VISIBLE else View.INVISIBLE
        CountLabelArea.visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    val MB = 1024 * 1024
    val KB = 1024

    fun setSpeed(rate: Double, speed: TextView, label: TextView) {
        val r: Double
        val l: String
        when {
            rate * 8 > MB -> {
                r = (rate * 8) / (1024 * 1024)
                l = "Mbps"
            }
            rate * 8 > KB -> {
                r = (rate * 8) / KB
                l = "Kbps"
            }
            else -> {
                r = rate * 8
                l = "bps"
            }
        }

        speed.text = String.format("%.1f", r)
        label.text = l
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            10169 -> {
                if (resultCode == AppCompatActivity.RESULT_OK)
                    startService(Intent(this, ZitiVPNService::class.java).setAction("start"))
            }
            10168 -> {
                if (resultCode == AppCompatActivity.RESULT_OK)
                    startService(Intent(this, ZitiVPNService::class.java).setAction("stop"))
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

}