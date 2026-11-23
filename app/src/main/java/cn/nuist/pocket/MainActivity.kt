package cn.nuist.pocket

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import cn.nuist.pocket.data.*
import cn.nuist.pocket.data.Period
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val Blue: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Canvas: Color @Composable get() = MaterialTheme.colorScheme.background
private val SoftBlue: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer
private val ChinaZone = ZoneId.of("Asia/Shanghai")
private val DayNames = listOf("一", "二", "三", "四", "五", "六", "日")
private val ShortDate = DateTimeFormatter.ofPattern("M月d日")

class MainActivity : ComponentActivity() {
    private lateinit var navigation: PocketNavigation
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) { CacheMaintenance.removeStaleImageImports(cacheDir) }
        navigation = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PocketNavigation() as T
        })[PocketNavigation::class.java]
        if (navigation.pending == null) openIntent(intent)
        enableEdgeToEdge()
        setContent {
            val preferences = remember { AppPreferences(this) }
            val wallpapers = rememberWallpaperSession()
            var track by remember { mutableStateOf(preferences.studentTrack) }
            var appearanceRevision by remember { mutableIntStateOf(0) }
            val accent = remember(appearanceRevision) { preferences.themeAccent }
            val themeMode = remember(appearanceRevision) { preferences.themeMode }
            val widgetAppearance = remember { WidgetAppearance(this) }
            PocketTheme(accent, themeMode) {
                val primary = MaterialTheme.colorScheme.primary.toArgb()
                LaunchedEffect(primary) {
                    if (widgetAppearance.syncAppPrimary(primary) && widgetAppearance.followAppAccent) {
                        TodayWidgetProvider.requestRefresh(this@MainActivity)
                    }
                }
                key(track) {
                    PocketApp(navigation, preferences, wallpapers, appearanceRevision, { appearanceRevision++ }) { selected ->
                        if (selected != track) {
                            preferences.studentTrack = selected
                            SchoolCache(this, selected).markLoginRequired()
                            track = selected
                            getSystemService(android.app.NotificationManager::class.java).cancel(2100)
                            TodayWidgetProvider.requestRefresh(this)
                            lifecycleScope.launch(Dispatchers.IO) { refreshReminders(this@MainActivity) }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openIntent(intent)
    }
    private fun openIntent(intent: Intent) {
        if (intent.action == TodayWidgetProvider.OPEN_TODOS) navigation.open(0, widgetDate(intent), todos = true)
        else if (intent.action == TodayWidgetProvider.OPEN_TODAY) navigation.open(0, widgetDate(intent))
        else navigation.open(AppPreferences(this).homeTab.index)
    }
    private fun widgetDate(intent: Intent): String? = intent.getStringExtra("date")
        ?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
}

@Composable
private fun PocketApp(navigation: PocketNavigation, preferences: AppPreferences, wallpapers: WallpaperSession,
                      appearanceRevision: Int, onAppearanceChange: () -> Unit,
                      onTrackChange: (StudentTrack) -> Unit) {
    val context = LocalContext.current
    val track = preferences.studentTrack
    val isUpgrade = remember {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.lastUpdateTime > info.firstInstallTime
        }.getOrDefault(false)
    }
    var releasePending by rememberSaveable(BuildConfig.VERSION_CODE) {
        mutableStateOf(shouldShowReleaseNotes(BuildConfig.VERSION_CODE, preferences.seenReleaseVersion, isUpgrade))
    }
    LaunchedEffect(Unit) {
        if (!isUpgrade) preferences.seenReleaseVersion = BuildConfig.VERSION_CODE
    }
    val store = remember { PocketStore(context, track) }
    val cache = remember { SchoolCache(context, track) }
    val wallpaper = wallpapers.images
    val wallpaperFade = remember(appearanceRevision) { preferences.wallpaperFade / 100f }
    val wallpaperScope = remember(appearanceRevision) { preferences.wallpaperScope }
    val wallpaperBlur = remember(appearanceRevision) { preferences.timetableWallpaperBlur }
    val scheduleMulticolor = remember(appearanceRevision) { preferences.scheduleMulticolor }
    val timetableOpacity = remember(preferences) { TimetableOpacity(preferences) }
    val reminderScope = rememberCoroutineScope()
    var notificationRevision by remember { mutableIntStateOf(0) }
    var showNotificationIntro by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationRevision++
        reminderScope.launch(Dispatchers.IO) { refreshReminders(context) }
    }
    fun askForNotifications() {
        preferences.permissionAsked = true
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var sessionGeneration by rememberSaveable { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                TodayWidgetProvider.requestRefresh(context)
                notificationRevision++
                reminderScope.launch(Dispatchers.IO) { refreshReminders(context) }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val now by produceState(LocalDateTime.now(ChinaZone), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = LocalDateTime.now(ChinaZone); delay(30_000) }
        }
    }
    val today = now.toLocalDate()
    val defaultPeriods = remember { store.periods }
    val demoCourses = remember(previewMode) { if (previewMode) store.demoCourses else emptyList() }
    val rooms = remember(previewMode) { if (previewMode) store.demoRooms else ClassroomPage(emptyList(), 0, 1, 10) }
    val demoExams = remember(previewMode) { if (previewMode) store.demoExams else emptyList() }
    var live by remember { mutableStateOf(cache.load()) }
    val api = remember { SchoolApi(SchoolConnection(track) { WebSettings.getDefaultUserAgent(context) }, track,
        { graduateFirstMonday(it, preferences.graduateMonday(it)) }, { store.periods }) }
    var schoolConnected by rememberSaveable { mutableStateOf(live != null && !cache.loginRequired) }
    var connectedTerm by rememberSaveable { mutableStateOf(live?.semester?.code) }
    var schoolBusy by remember { mutableStateOf(false) }
    var schoolMessage by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf(store.savedCourses()) }
    var firstMonday by remember { mutableStateOf(store.firstMonday()) }
    var tab by rememberSaveable { mutableIntStateOf(preferences.homeTab.index) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsStartPage by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    val globalWallpaper = wallpaperScope == WallpaperScope.IMMERSIVE
    var importing by remember { mutableStateOf(false) }
    fun openSettingsPage(page: SettingsPage) {
        if (!schoolBusy && !importing) {
            settingsStartPage = page
            showSettings = true
        }
    }
    fun openSettings() = openSettingsPage(SettingsPage.HOME)
    fun openSchoolAccount() = openSettingsPage(SettingsPage.ACCOUNT)
    var importError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val todoStore = remember { TodoStore(context) }
    val todoMutex = remember { Mutex() }
    var todos by remember { mutableStateOf<List<TodoItem>?>(null) }
    var todoBusy by remember { mutableStateOf(false) }
    var todoError by remember { mutableStateOf<String?>(null) }
    var showTodos by rememberSaveable { mutableStateOf(false) }
    var openPendingTodos by rememberSaveable { mutableStateOf(false) }
    var todoOpenRevision by rememberSaveable { mutableIntStateOf(0) }
    var todoDate by rememberSaveable { mutableStateOf(today.toString()) }
    var todoEditorEntry by rememberSaveable { mutableStateOf<String?>(null) }
    fun openTodos(date: LocalDate, editor: String? = null) {
        todoDate = date.toString()
        openPendingTodos = false
        todoEditorEntry = editor
        todoOpenRevision++
        showTodos = true
    }
    fun loadTodos() {
        scope.launch { todoMutex.withLock {
            todoBusy = true; todoError = null
            try { todos = withContext(Dispatchers.IO) { todoStore.load() } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { todoError = "无法读取已有待办，原文件已保留。请重新读取；暂不覆盖保存。" }
            finally { todoBusy = false }
        } }
    }
    fun changeTodos(transform: (List<TodoItem>) -> List<TodoItem>, finished: (Boolean) -> Unit = {}) {
        scope.launch { todoMutex.withLock {
            val current = todos ?: return@withLock
            todoBusy = true; todoError = null
            try {
                val next = transform(current)
                withContext(Dispatchers.IO) { todoStore.save(next) }
                todos = next; TodayWidgetProvider.requestRefresh(context); finished(true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                todoError = if (error is IllegalArgumentException) error.message else "保存失败，原有待办已保留，请重试。"
                finished(false)
            } finally { todoBusy = false }
        } }
    }
    val todoActions = TodoActions(
        save = { item, done -> changeTodos({ list -> list.filterNot { it.id == item.id } + item }, done) },
        delete = { id, done -> changeTodos({ list -> list.filterNot { it.id == id } }, done) },
        toggle = { item -> changeTodos({ list -> list.map { if (it.id == item.id) it.copy(completed = !it.completed) else it } }) },
    )
    LaunchedEffect(Unit) { loadTodos() }
    var todayFocusGeneration by rememberSaveable { mutableIntStateOf(0) }
    var widgetDateRequest by rememberSaveable { mutableStateOf<String?>(null) }
    val launchRequest = navigation.pending
    LaunchedEffect(launchRequest) {
        if (launchRequest != null && navigation.consume(launchRequest)) {
            tab = track.visibleTab(launchRequest.tab); showSettings = false; widgetDateRequest = launchRequest.date
            if (launchRequest.todos) {
                todoDate = launchRequest.date ?: today.toString(); openPendingTodos = true
                todoEditorEntry = null; todoOpenRevision++; showTodos = true
            } else showTodos = false
            if (tab == 0) todayFocusGeneration++
        }
    }
    // All source changes, including clearing an account and changing the imported calendar,
    // refresh the same local data used by the home-screen widget.
    LaunchedEffect(live, imported, firstMonday, schoolConnected) {
        TodayWidgetProvider.requestRefresh(context)
        withContext(Dispatchers.IO) { refreshReminders(context) }
        if ((live != null || imported != null) && preferences.eveningEnabled && !preferences.permissionAsked && !EveningReminder.allowed(context)) {
            showNotificationIntro = true
        }
    }
    fun syncSchool() {
        if (schoolBusy || importing) return
        schoolBusy = true
        schoolMessage = null
        scope.launch {
            val restoring = !schoolConnected
            var succeeded = false
            try {
                val data = api.sync()
                withContext(Dispatchers.IO) { cache.save(data) }
                live = data
                schoolConnected = true
                connectedTerm = data.semester.code
                previewMode = false
                if (restoring) sessionGeneration++
                succeeded = true
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (error is SchoolLoginRequired) { schoolConnected = false; cache.markLoginRequired() }
                if (error is GraduateCalendarRequired) connectedTerm = error.semesterCode
                schoolMessage = schoolErrorText(error)
            } finally { schoolBusy = false }
            if (succeeded) snackbar.showSnackbar("课表和考试已同步到本机")
        }
    }
    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && StudentTrack.read(result.data?.getStringExtra("studentTrack")) == track) {
            connectedTerm = result.data?.getStringExtra("semester")
            schoolConnected = true; syncSchool()
        }
    }
    fun startSchoolLogin() {
        // An explicit login starts a new school-data session; never display a previous account's cache in it.
        preferences.newClassScheduleSession()
        cache.clear(); live = null; schoolConnected = false
        ClassReminders.requestRefresh(context)
        connectedTerm = null; previewMode = false; sessionGeneration++
        showSettings = false
        loginLauncher.launch(Intent(context, SchoolLoginActivity::class.java).putExtra("studentTrack", track.name))
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw SchoolDataException("无法读取所选文件")
                    store.importCourses(stream)
                }
                imported = result
                // Keep a manually imported timetable separate from the school cache.
                live = null; cache.clear()
                previewMode = false; sessionGeneration++
                importing = false
                snackbar.showSnackbar("已导入${result.size}条排课记录，请核对第一周起始日")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                importError = if (error is SchoolDataException) error.message else "读取文件失败，请选择课表JSON响应文件"
            } finally { importing = false }
        }
    }
    val periods = live?.periods ?: defaultPeriods
    val exams = live?.exams ?: demoExams
    val semester = remember(live, imported, firstMonday) {
        live?.semester ?: Semester(imported?.firstOrNull()?.semester ?: DEFAULT_LOCAL_SEMESTER, firstMonday)
    }
    val overrideStore = remember(track) { CourseOverrideStore(context, track) }
    var rules by remember(track, semester.code, sessionGeneration) { mutableStateOf(overrideStore.load(semester.code)) }
    val rawCourses = live?.courses ?: imported.orEmpty()
    val ownCourses = remember(rawCourses, rules, semester.code) { applyCourseOverrides(rawCourses, semester.code, rules) }
    val hasOwnSchedule = live != null || imported != null || rules.customCourses.isNotEmpty()
    val demo = !hasOwnSchedule && previewMode
    val courses = if (demo) demoCourses else ownCourses
    val lastWeek = remember(rawCourses, courses, rules.sessions) {
        maxOf(teachingWeekLimit(rawCourses), teachingWeekLimit(courses),
            rules.sessions.keys.maxOfOrNull { it.week } ?: 0)
    }
    val rawNames = remember(rawCourses) { rawCourses.associate { it.courseKey to it.name } }
    var initialCourseSheet by remember(semester.code, sessionGeneration) { mutableStateOf<CourseSheetPage?>(null) }
    var suspendedDates by remember(track, semester.code, sessionGeneration) { mutableStateOf(preferences.suspendedDates(track, semester.code)) }
    fun refreshLocalSchedule() {
        TodayWidgetProvider.requestRefresh(context)
        ClassReminders.requestRefresh(context)
        reminderScope.launch(Dispatchers.IO) { EveningReminder.schedule(context) }
    }
    fun setDaySuspended(date: LocalDate, suspended: Boolean) {
        preferences.setSuspendedDate(track, semester.code, date, suspended)
        suspendedDates = preferences.suspendedDates(track, semester.code)
        refreshLocalSchedule()
    }
    val courseEdits = CourseEdits(rules, rawNames, ownCourses, lastWeek) { change ->
        try {
            rules = overrideStore.update(semester.code, change)
            refreshLocalSchedule()
            true
        } catch (_: java.io.IOException) {
            scope.launch { snackbar.showSnackbar("课程调整未保存，请重试。") }
            false
        }
    }
    val schoolNotice = live?.let { "${if (!schoolConnected) "学校连接待验证 · " else ""}学校缓存 · 更新于 ${formatSchoolTime(it.fetchedAt)}" }
    val roomSemester = live?.semester ?: AcademicCalendar.semester(connectedTerm) ?: semester

    val immersive = globalWallpaper && wallpaper != null
    val globalViewport = remember { GlassViewport() }
    val dark = LocalPocketDark.current
    val canvasColor = Canvas
    val surfaceColor = MaterialTheme.colorScheme.surface
    val wallpaperPlacement = remember(globalViewport) { Modifier.onPlaced { globalViewport.coordinates = it } }
    val topScrim = remember(dark, surfaceColor) {
        Brush.verticalGradient(listOf(if (dark) Color.Black.copy(alpha = 0.62f) else surfaceColor.copy(alpha = 0.78f),
            Color.Transparent))
    }
    val bottomScrim = remember(dark, surfaceColor) {
        Brush.verticalGradient(listOf(Color.Transparent,
            if (dark) Color.Black.copy(alpha = 0.72f) else surfaceColor.copy(alpha = 0.85f)))
    }
    val bodyScrim = remember(dark, canvasColor) {
        val shade = canvasColor.copy(alpha = if (dark) 0.20f else 0.13f)
        Brush.verticalGradient(0f to Color.Transparent, 0.15f to shade, 0.92f to shade, 1f to Color.Transparent)
    }
    val window = LocalActivity.current?.window
    DisposableEffect(window, immersive) {
        val previous = window?.isNavigationBarContrastEnforced
        if (immersive && window != null) window.isNavigationBarContrastEnforced = false
        onDispose {
            if (immersive && window != null && previous != null) window.isNavigationBarContrastEnforced = previous
        }
    }
    Box(Modifier.fillMaxSize().background(canvasColor)) {
      if (immersive) {
        TimetableWallpaper(wallpaper?.original, wallpaperFade, wallpaperBlur, wallpaperPlacement)
        val statusHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(Modifier.matchParentSize().padding(top = statusHeight + 52.dp, bottom = navigationInset + 80.dp)
            .background(bodyScrim))
        Box(Modifier.fillMaxWidth().height(statusHeight + 92.dp).background(topScrim))
      }
      CompositionLocalProvider(LocalPocketImmersive provides immersive) {
      Scaffold(
        containerColor = if (immersive) Color.Transparent else canvasColor,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val background = if (immersive) Modifier.background(bottomScrim) else Modifier
            NavigationBar(modifier = background, containerColor = if (immersive) Color.Transparent else surfaceColor,
                tonalElevation = 0.dp) {
                listOf(Triple(0, "日程", Icons.Outlined.Home), Triple(3, "课程表", Icons.Outlined.DateRange),
                    Triple(1, "教室", Icons.Outlined.Place), Triple(2, "考试", Icons.AutoMirrored.Outlined.List))
                    .filter { (index, _, _) -> track.isTabVisible(index) }.forEach { (index, title, icon) ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { PocketNavigationIcon(icon, title, tab == index) }, label = { Text(title) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent, selectedIconColor = Blue,
                            selectedTextColor = if (immersive) Ink else Blue,
                            unselectedIconColor = if (immersive) Ink else Muted,
                            unselectedTextColor = if (immersive) Ink else Muted))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Account/source changes dispose previous queries and their saved tab state.
            key(sessionGeneration) {
              val tabStates = rememberSaveableStateHolder()
              AnimatedContent(targetState = tab, modifier = Modifier.fillMaxSize(), transitionSpec = {
                  fadeIn(tween(150)) togetherWith fadeOut(tween(120))
              }, label = "主页面切换") { displayedTab ->
              tabStates.SaveableStateProvider(displayedTab) {
                when (displayedTab) {
                    0 -> if (!hasOwnSchedule && !previewMode && todos.orEmpty().isEmpty()) {
                        FirstUseScreen(schoolConnected, busy = schoolBusy || importing, onConnect = { if (schoolConnected) syncSchool() else startSchoolLogin() },
                            onPreview = { previewMode = true; sessionGeneration++ },
                            pageTitle = "日程", onSettings = ::openSettings, onSwitchTrack = ::openSchoolAccount, settingsEnabled = !schoolBusy && !importing,
                            todoContent = { TodoPreview(todos, today, todoError) { openTodos(today) } })
                    } else key(todayFocusGeneration) {
                        ScheduleScreen(courses, periods, semester, now, demo, schoolNotice,
                            widgetDateRequest?.let(LocalDate::parse), todos, todoError, ::openSettings, !schoolBusy && !importing,
                            suspendedDates, ::setDaySuspended, scheduleMulticolor, if (demo) null else courseEdits,
                            todoBusy, todoActions.toggle, onEditTodo = { openTodos(it.date, it.id) }, onTodos = ::openTodos)
                    }
                    1 -> if (track.supportsRooms) RoomsScreen(rooms, periods, roomSemester, api,
                        schoolConnected && AcademicCalendar.semester(connectedTerm) != null, today, previewMode,
                        onLogin = { if (live != null) syncSchool() else startSchoolLogin() }, onSessionExpired = { schoolConnected = false; cache.markLoginRequired(); sessionGeneration++ },
                        onSettings = ::openSettings, settingsEnabled = !schoolBusy && !importing)
                    2 -> if (live == null && !previewMode) {
                        FirstUseScreen(schoolConnected, busy = schoolBusy || importing, onConnect = { if (schoolConnected) syncSchool() else startSchoolLogin() },
                            onPreview = { previewMode = true; sessionGeneration++ }, pageTitle = "考试", onSettings = ::openSettings, onSwitchTrack = ::openSchoolAccount, settingsEnabled = !schoolBusy && !importing)
                    } else ExamsScreen(exams, now, schoolNotice, ::openSettings, !schoolBusy && !importing)
                    3 -> if (!hasOwnSchedule && !previewMode) {
                        FirstUseScreen(schoolConnected, busy = schoolBusy || importing, onConnect = { if (schoolConnected) syncSchool() else startSchoolLogin() },
                            onPreview = { previewMode = true; sessionGeneration++ }, pageTitle = "课程表", onSettings = ::openSettings, onSwitchTrack = ::openSchoolAccount, settingsEnabled = !schoolBusy && !importing,
                            onAddCourse = {
                                val date = if (semester.weekOn(today) in 1..lastWeek) today else semester.firstMonday
                                initialCourseSheet = newCourseSheet(date, 1, periods)
                            })
                    } else TimetableScreen(courses, periods, semester, today, demo, preferences, ::openSettings, !schoolBusy && !importing,
                        wallpaper, wallpaperFade, suspendedDates, appearanceRevision = appearanceRevision, opacity = timetableOpacity,
                        edits = if (demo) null else courseEdits, immersiveViewport = if (immersive) globalViewport else null)
                }
              }
              }
            }
        }
      }
      }
    }
    CourseSheets(initialCourseSheet, { initialCourseSheet = it }, courseEdits, semester, periods, today,
        semester.weekOn(today).coerceIn(1, lastWeek))
    if (showTodos) key(todoOpenRevision) {
        TodoScreen(todos, LocalDate.parse(todoDate), today, openPendingTodos, todoBusy, todoError, ::loadTodos,
            todoActions, initialEditor = todoEditorEntry) { showTodos = false }
    }
    if (showSettings) SettingsScreen(preferences,
        SettingsStatus(schoolConnected, schoolBusy, importing,
            live?.let { "${it.semester.code} · ${formatSchoolTime(it.fetchedAt)}" }, imported != null, live?.semester?.firstMonday ?: firstMonday, previewMode,
            track, connectedTerm,
            calendarDate = if (track == StudentTrack.GRADUATE) connectedTerm?.let { graduateFirstMonday(it, preferences.graduateMonday(it)) }
                ?: live?.semester?.firstMonday ?: firstMonday else firstMonday),
        SettingsActions(
            close = { showSettings = false }, login = { startSchoolLogin() }, sync = { syncSchool() },
            logout = {
                schoolBusy = true
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush(); schoolBusy = false
                }
                WebStorage.getInstance().deleteAllData()
                preferences.newClassScheduleSession()
                cache.clear(); store.clear(); live = null; imported = null; schoolConnected = false
                ClassReminders.requestRefresh(context)
                connectedTerm = null; previewMode = false; sessionGeneration++
                firstMonday = store.firstMonday()
            },
            widget = {
                val manager = AppWidgetManager.getInstance(context)
                val requested = manager.isRequestPinAppWidgetSupported &&
                    manager.requestPinAppWidget(ComponentName(context, TodayWidgetProvider::class.java), null, null)
                showSettings = false
                if (!requested) scope.launch { snackbar.showSnackbar("长按桌面空白处，在小组件中添加 NUIST Pocket 今日课表") }
            },
            chooseImport = {
                showSettings = false
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
            calendar = { selected ->
                if (selected.dayOfWeek == DayOfWeek.MONDAY) {
                    if (track == StudentTrack.GRADUATE) {
                        val code = connectedTerm ?: live?.semester?.code
                        if (code != null) {
                            preferences.saveGraduateMonday(code, selected)
                            live?.takeIf { it.semester.code == code }?.let { current ->
                                val changed = current.copy(semester = current.semester.copy(firstMonday = selected))
                                cache.save(changed, verified = false)
                                live = changed
                            }
                        } else scope.launch { snackbar.showSnackbar("请先登录并验证当前研究生学期") }
                    }
                    firstMonday = selected
                    store.saveFirstMonday(selected)
                } else scope.launch { snackbar.showSnackbar("请选择第一教学周的周一") }
            },
            clearImport = { store.clear(); imported = null; sessionGeneration++; firstMonday = store.firstMonday(); showSettings = false },
            preview = { previewMode = !previewMode; sessionGeneration++; showSettings = false },
            schoolWeb = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (track == StudentTrack.GRADUATE) GraduateRequests.PORTAL else "https://jwxt.nuist.edu.cn/"))) },
            permission = { askForNotifications() },
            reminder = { reminderScope.launch(Dispatchers.IO) { refreshReminders(context) } },
            appearance = onAppearanceChange,
        ), notificationRevision, wallpapers, timetableOpacity, today, settingsStartPage, onTrackChange)
    if (releasePending && !showSettings && !showTodos && !schoolBusy && !importing && importError == null) {
        ReleaseHighlights {
            preferences.seenReleaseVersion = BuildConfig.VERSION_CODE
            releasePending = false
        }
    }
    if (showNotificationIntro && !releasePending && !showSettings && !showTodos && !schoolBusy && !importing && importError == null) AlertDialog(onDismissRequest = {
        preferences.permissionAsked = true; showNotificationIntro = false
    }, title = { Text("晚间看看明天的安排") }, text = {
        Text("每天21:00左右提醒明天第一节课；没有已排课程时送上一句晚安。使用本机课表，无需闹钟权限。可在设置中调整时间或关闭，需要你允许系统通知。", style = tabularTextStyle())
    }, confirmButton = { TextButton(onClick = { showNotificationIntro = false; askForNotifications() }) { Text("允许通知") } },
        dismissButton = { TextButton(onClick = { preferences.permissionAsked = true; showNotificationIntro = false }) { Text("稍后再说") } })
    if (importError != null) AlertDialog(onDismissRequest = { importError = null },
        title = { Text("课表未导入") }, text = { Text(importError.orEmpty()) },
        confirmButton = { TextButton(onClick = { importError = null }) { Text("知道了") } })
    if ((schoolBusy || importing) && !showSettings) AlertDialog(onDismissRequest = {},
        title = { Text(if (importing) "正在导入课表" else "正在同步学校数据") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (importing) "正在读取所选课表文件。" else "获取课表、节次与考试安排，请稍候。")
        } }, confirmButton = {})
    if (schoolMessage != null) AlertDialog(onDismissRequest = { schoolMessage = null }, title = { Text("学校连接提示") },
        text = { Text(schoolMessage.orEmpty()) }, confirmButton = {
            if (!schoolConnected && cache.loginRequired) TextButton(onClick = { schoolMessage = null; startSchoolLogin() }) { Text("重新登录") }
            else TextButton(onClick = { schoolMessage = null }) { Text("知道了") }
        }, dismissButton = { if (!schoolConnected && cache.loginRequired) TextButton(onClick = { schoolMessage = null }) { Text("继续看缓存") } })
}

private fun schoolErrorText(error: Exception): String = when (error) {
    is GraduateCalendarRequired -> error.message ?: "请先补充研究生学期起点"
    is SchoolLoginRequired -> "学校连接需要重新验证，本次未更新。已保存的课表和考试仍可离线查看；请进入学校页面验证现有登录，需要时完成登录。"
    is SchoolConnectionError -> error.message ?: "连接学校失败，请重试"
    is SchoolDataException -> "学校返回的数据暂时无法识别，未替换本机缓存；请反馈出现问题的页面。"
    else -> "查询未完成，请检查网络后重试"
}

private fun formatSchoolTime(instant: String): String = runCatching {
    Instant.parse(instant).atZone(ChinaZone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
}.getOrDefault("本机缓存")

@Composable
private fun FirstUseScreen(connected: Boolean, busy: Boolean, onConnect: () -> Unit, onPreview: () -> Unit,
                           pageTitle: String, onSettings: () -> Unit, onSwitchTrack: () -> Unit, settingsEnabled: Boolean,
                           todoContent: (@Composable () -> Unit)? = null, onAddCourse: (() -> Unit)? = null) {
    val track = AppPreferences(LocalContext.current).studentTrack
    Column(Modifier.fillMaxSize()) {
      PocketPageHeader(onSettings, settingsEnabled, sideWidth = 48.dp,
          leading = { Icon(Icons.Outlined.Home, null, Modifier.padding(12.dp).size(24.dp), tint = Blue) }) {
          Text(pageTitle, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp, maxLines = 1)
      }
      LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SectionHeading(if (connected) "同步你的校园安排" else "你好，南信大",
                if (track.supportsRooms) "课表、考试与自习教室，随时放进口袋" else "课表、考试与个人待办，随时放进口袋")
        }
        if (todoContent != null) item(key = "personal-todos") { todoContent() }
        item {
            PocketCard(shape = PocketShapes.Hero) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("当前：${track.title}", modifier = Modifier.weight(1f), fontSize = 13.sp, color = Muted)
                        TextButton(enabled = settingsEnabled, onClick = onSwitchTrack) { Text("切换培养类型") }
                    }
                    Text(if (connected) "登录已验证，同步后就能查看安排。" else "连接学校账号，保存自己的课表和考试。", color = Muted, fontSize = 14.sp)
                    Button(shape = PocketShapes.Control, enabled = !busy, onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "请稍候…" else if (connected) "同步课表和考试" else "登录学校账号")
                    }
                    TextButton(enabled = !busy, onClick = onPreview, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("先看看演示") }
                    if (onAddCourse != null) OutlinedButton(onClick = onAddCourse, enabled = !busy, shape = PocketShapes.Control,
                        modifier = Modifier.fillMaxWidth()) { Text("添加自定义课程") }
                    Text("演示课程和考试均为虚构数据。待办无需登录，可随时在日程中使用。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    HorizontalDivider()
                    QuickStartSteps()
                }
            }
        }
      }
    }
}

@Composable
private fun PageHeading(title: String, status: String, info: String, onSettings: () -> Unit,
                        settingsEnabled: Boolean) {
    var showInfo by rememberSaveable { mutableStateOf(false) }
    PocketPageHeader(onSettings, settingsEnabled, leading = {
        if (status == "学校缓存") {
            IconButton(onClick = { showInfo = true }) {
                Icon(Icons.Outlined.Info, contentDescription = "数据来源与同步时间", tint = Muted, modifier = Modifier.size(18.dp))
            }
        } else {
            TextButton(onClick = { showInfo = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(if ("待验证" in status) "待验证" else status, fontSize = 12.sp)
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Outlined.Info, contentDescription = "数据与说明", Modifier.size(16.dp))
            }
        }
    }) {
        Text(title, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
    }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("$title · 信息") },
        text = { Text(info, Modifier.verticalScroll(rememberScrollState()), lineHeight = 22.sp, style = tabularTextStyle()) },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("知道了") } })
}

private fun schoolStatus(notice: String?): String = when {
    notice == null -> "演示"
    "待验证" in notice -> "连接待验证"
    else -> "学校缓存"
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(subtitle, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp), style = tabularTextStyle())
    }
}

@Composable
private fun ScheduleScreen(courses: List<CourseSlot>, periods: List<Period>, semester: Semester, now: LocalDateTime, demo: Boolean, schoolNotice: String? = null, focusDate: LocalDate? = null,
                           todos: List<TodoItem>?, todoError: String?, onSettings: () -> Unit, settingsEnabled: Boolean,
                           suspendedDates: Set<LocalDate>, onSuspend: (LocalDate, Boolean) -> Unit,
                           multicolor: Boolean, courseEdits: CourseEdits?, todoBusy: Boolean,
                           onToggleTodo: (TodoItem) -> Unit, onEditTodo: (TodoItem) -> Unit,
                           onTodos: (LocalDate, String?) -> Unit) {
    val today = now.toLocalDate()
    val dayIndicatorHeight = with(LocalDensity.current) { 18.sp.toDp() }
    val maxWeek = courseEdits?.lastWeek ?: teachingWeekLimit(courses)
    var week by rememberSaveable(semester.code, semester.firstMonday.toString()) {
        mutableIntStateOf(semester.weekOn(focusDate ?: today).coerceIn(1, maxWeek))
    }
    var selectedDay by rememberSaveable { mutableIntStateOf((focusDate ?: today).dayOfWeek.value) }
    var previousToday by rememberSaveable(semester.code) { mutableStateOf(today.toString()) }
    LaunchedEffect(today) {
        val selectedDate = semester.dateOf(week, selectedDay)
        val nextDate = followToday(selectedDate, LocalDate.parse(previousToday), today)
        if (nextDate != selectedDate && semester.weekOn(nextDate) in 1..maxWeek) {
            week = semester.weekOn(nextDate); selectedDay = nextDate.dayOfWeek.value
        }
        previousToday = today.toString()
    }
    var courseSheet by remember(semester.code) { mutableStateOf<CourseSheetPage?>(null) }
    val overview = remember(courses) { overviewCourses(courses) }
    val active = remember(overview, week) { overview.filter { week in it.weeks } }
    val date = semester.dateOf(week, selectedDay)
    var previousDate by remember { mutableStateOf(date) }
    val (direction, dateProgress) = remember(date) {
        val change = date.compareTo(previousDate).coerceIn(-1, 1)
        change to Animatable(if (change == 0) 1f else 0f)
    }
    LaunchedEffect(date) {
        previousDate = date
        dateProgress.animateTo(1f, tween(200))
    }
    val dayMotion = Modifier.graphicsLayer {
        val progress = dateProgress.value
        translationX = direction * 18.dp.toPx() * (1f - progress)
        alpha = progress
    }
    val immersive = LocalPocketImmersive.current
    val statusBadge = if (immersive) {
        val fill = MaterialTheme.colorScheme.surface.copy(alpha = if (LocalPocketDark.current) 0.62f else 0.66f)
        Modifier.background(fill, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp)
    } else Modifier
    val dayCourses = remember(active, selectedDay, date, suspendedDates) {
        if (date in suspendedDates) emptyList() else active.filter { it.weekday == selectedDay }.sortedBy { it.firstPeriod }
    }
    val unassigned = remember(courses) { courses.filterNot { it.arranged } }
    val finishedCount = remember(dayCourses, periods, date, now) {
        dayCourses.count { courseHasEndedToday(it, periods, date, now) }
    }
    val dayTodos = remember(todos, date) { todos.orEmpty().filter { it.date == date }.sortedWith(todoOrder) }
    val timeline = remember(dayCourses, dayTodos, periods) {
        val entries: List<DayTimelineEntry> = dayCourses.mapIndexed { index, slot -> DayTimelineEntry.Course(slot, index, slot.times(periods).first) } +
            dayTodos.filter { it.time != null }.map { DayTimelineEntry.Task(it) }
        entries.sortedBy { it.time ?: LocalTime.MAX }
    }
    var choosingWeek by rememberSaveable { mutableStateOf(false) }
    var showDayActions by remember(date) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeading("日程", if (schoolNotice != null) schoolStatus(schoolNotice) else if (demo) "演示" else "本机课表",
            schoolNotice ?: if (demo) "演示课表，均为虚构课程，未连接学校账号。" else "本机导入课表，离线显示，未自动同步学校。",
            onSettings, settingsEnabled)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "week-picker", contentType = "week-picker") {
            PocketCard(shape = PocketShapes.Hero) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(enabled = week > 1, onClick = { week-- }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一周", Modifier.size(18.dp))
                        }
                        TeachingWeekHeading(week,
                            "${semester.dateOf(week, 1).format(ShortDate)} — ${semester.dateOf(week, 7).format(ShortDate)}",
                            Modifier.weight(1f), onClick = { choosingWeek = true })
                        IconButton(enabled = week < maxWeek, onClick = { week++ }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "下一周", Modifier.size(18.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        (1..7).forEach { day ->
                            val d = semester.dateOf(week, day)
                            val chosen = selectedDay == day
                            val dayColor by animateColorAsState(if (chosen) Blue else Color.Transparent, animationSpec = tween(170), label = "日期选择")
                            val selectionScale = animateFloatAsState(if (chosen) 1f else 0.965f, tween(170), label = "选中日期缩放")
                            val press = rememberPocketPress()
                            Surface(onClick = { selectedDay = day }, color = dayColor,
                                shape = PocketShapes.Control, interactionSource = press.interaction,
                                modifier = press.modifier(Modifier.weight(1f)).graphicsLayer {
                                    scaleX = selectionScale.value
                                    scaleY = selectionScale.value
                                }) {
                                Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(DayNames[day - 1], fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (chosen) MaterialTheme.colorScheme.onPrimary else Muted)
                                    Text(d.dayOfMonth.toString(), fontSize = 18.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                                        color = if (chosen) MaterialTheme.colorScheme.onPrimary else Ink, style = tabularTextStyle())
                                    val indicatorColor = if (chosen) MaterialTheme.colorScheme.onPrimary else Blue
                                    Box(Modifier.height(dayIndicatorHeight), contentAlignment = Alignment.Center) {
                                        if (d == today) {
                                            Text("今天", fontSize = 10.sp, lineHeight = 14.sp, color = indicatorColor)
                                        } else if ((d !in suspendedDates && active.any { it.weekday == day }) || todos.orEmpty().any { it.date == d && !it.completed }) {
                                            Box(Modifier.size(4.dp).background(indicatorColor, CircleShape))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val currentWeek = semester.weekOn(today)
                    if (date != today && currentWeek in 1..maxWeek) TextButton(onClick = { week = currentWeek; selectedDay = today.dayOfWeek.value },
                        modifier = Modifier.align(Alignment.End)) { Text("回到今天", fontSize = 12.sp) }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().then(dayMotion)) {
                Text("${date.format(ShortDate)} · 星期${DayNames[selectedDay - 1]}", fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), fontSize = 17.sp, lineHeight = 24.sp, style = tabularTextStyle())
                Text(if (date in suspendedDates) "已停课" else if (dayCourses.isNotEmpty() && finishedCount == dayCourses.size) "已上完"
                    else if (finishedCount > 0) "剩余${dayCourses.size - finishedCount}门" else "${dayCourses.size} 门课程",
                    modifier = statusBadge, color = if (date in suspendedDates) Blue else if (immersive) Ink else Muted,
                    fontSize = 12.sp, fontWeight = if (immersive) FontWeight.Medium else null, style = tabularTextStyle())
                if (!demo) Box {
                    IconButton(onClick = { showDayActions = true }) {
                        Icon(Icons.Outlined.MoreVert, "当天操作", tint = if (immersive) Ink else Muted)
                    }
                    DropdownMenu(expanded = showDayActions, onDismissRequest = { showDayActions = false }) {
                        DropdownMenuItem(text = { Text(if (date in suspendedDates) "恢复当天课程与提醒" else "当天全部停课") },
                            onClick = { showDayActions = false; onSuspend(date, date !in suspendedDates) })
                    }
                }
            }
        }
        if (date in suspendedDates) item {
            Box(dayMotion) {
                EmptyCard("当天已设置停课", "课程提醒已暂停，相关声音预约已取消；恢复课程后需要重新预约。", Icons.Outlined.CheckCircle)
            }
        }
        item(key = "all-day-todos", contentType = "todos") {
            AllDayTodos(dayTodos.filter { it.time == null }, todoBusy, todoError, todos != null, onToggleTodo, onEditTodo,
                onAdd = { onTodos(date, "") }, onOpen = { onTodos(date, null) }, modifier = dayMotion)
        }
        if (timeline.isEmpty() && date !in suspendedDates) item {
            Box(dayMotion) { EmptyCard("这一天没有定时安排", "可以添加待办，或切换到其他日期。", Icons.Outlined.CheckCircle) }
        }
        items(timeline, key = { it.key }, contentType = { if (it is DayTimelineEntry.Course) "course" else "timed-todo" }) { row ->
            when (row) {
                is DayTimelineEntry.Course -> DayCourseCard(row.slot, periods,
                    finished = courseHasEndedToday(row.slot, periods, date, now), multicolor = multicolor, modifier = dayMotion) {
                    courseSheet = CourseSheetPage.Details(listOf(OverviewCourseGroup(listOf(row.slot))), week)
                }
                is DayTimelineEntry.Task -> TimedTodoCard(row.item, todoBusy, onToggleTodo, onEditTodo, modifier = dayMotion)
            }
        }
        if (unassigned.isNotEmpty()) item(key = "unassigned", contentType = "unassigned") {
            PocketCard(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("待排时间 · ${unassigned.size}条", fontWeight = FontWeight.Medium, fontSize = 14.sp, style = tabularTextStyle())
                    Text(unassigned.joinToString("、") { it.displayName }, color = Muted, fontSize = 12.sp)
                }
            }
        }
        item(key = "week-overview", contentType = "week-overview") {
            WeekOverview(overview, week, semester, suspendedDates,
                onSelect = { courseSheet = CourseSheetPage.Details(listOf(it), week) })
        }
        }
    }
    if (choosingWeek) TeachingWeekPicker(week, maxWeek, semester, today,
        onSelect = { week = it; choosingWeek = false }, onDismiss = { choosingWeek = false })
    CourseSheets(courseSheet, { courseSheet = it }, courseEdits, semester, periods, today, week)
}

@Composable
private fun DayCourseCard(course: CourseSlot, periods: List<Period>, finished: Boolean, multicolor: Boolean,
                          modifier: Modifier = Modifier, onClick: () -> Unit) {
    val times = remember(course, periods) { course.times(periods) }
    val accent = if (multicolor) courseAccentColor(course.code, course.name, LocalPocketDark.current, course.customColorHex) else Blue
    PocketCard(onClick = onClick, modifier = modifier) {
        Row(Modifier.fillMaxWidth().alpha(if (finished) 0.50f else 1f).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(62.dp)) {
                Text(times.first?.toString() ?: "待定", color = Blue, fontWeight = FontWeight.Bold, fontSize = 17.sp, style = tabularTextStyle())
                Text(times.second?.toString() ?: "待定", color = Muted, fontSize = 12.sp, style = tabularTextStyle())
            }
            Box(Modifier.width(3.dp).height(62.dp).background(accent, CircleShape))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(course.displayName, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = PocketShapes.Compact) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Place, null, Modifier.size(12.dp), tint = Muted)
                        Text(course.room.ifBlank { "地点待定" }, fontSize = 12.sp, lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium, color = Muted, style = tabularTextStyle())
                    }
                }
                Text("第${course.firstPeriod}–${course.lastPeriod}节 · ${course.teacher.ifBlank { "教师待定" }}", fontSize = 11.sp, color = Muted, style = tabularTextStyle())
            }
        }
    }
}

@Composable
private fun WeekOverview(courses: List<CourseSlot>, week: Int, semester: Semester, suspendedDates: Set<LocalDate>, onSelect: (OverviewCourseGroup) -> Unit) {
    var showInactive by rememberSaveable { mutableStateOf(true) }
    var inactiveDay by rememberSaveable { mutableStateOf<Int?>(null) }
    val groups = remember(courses) { overviewGroups(courses) }
    val byDay = remember(groups, week) { (1..7).associateWith { overviewDay(groups, week, it) } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("一周总览", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text("非本周", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(end = 6.dp))
            Switch(checked = showInactive, onCheckedChange = { showInactive = it })
        }
        // Seven bounded columns are measured together, so horizontal scrolling cannot shrink
        // the final LazyColumn item and clamp its parent's vertical scroll position.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..7).forEach { day ->
                PocketCard(modifier = Modifier.width(124.dp)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("星期${DayNames[day - 1]}", fontWeight = FontWeight.Medium, color = Blue, fontSize = 13.sp)
                        val slots = byDay.getValue(day)
                        val suspended = semester.dateOf(week, day) in suspendedDates
                        if (suspended || slots.active.isEmpty()) Text(if (suspended) "已设置停课" else "本周无课", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
                        (if (suspended) emptyList() else slots.active).forEach { group ->
                            val course = group.course
                            Surface(onClick = { onSelect(group) }, color = SoftBlue, shape = PocketShapes.Compact) {
                                Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(course.displayName, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${course.firstPeriod}–${course.lastPeriod}节", color = Blue, fontSize = 11.sp, style = tabularTextStyle())
                                    Text(overviewLocation(group, week), fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (showInactive && slots.inactive.isNotEmpty()) Surface(onClick = { inactiveDay = day },
                            color = MaterialTheme.colorScheme.surfaceVariant, shape = PocketShapes.Compact) {
                            Text("非本周 ${slots.inactive.size} 门 ›", Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                color = Muted, fontSize = 11.sp, style = tabularTextStyle())
                        }
                    }
                }
            }
        }
    }
    inactiveDay?.let { day ->
        AlertDialog(onDismissRequest = { inactiveDay = null }, title = { Text("星期${DayNames[day - 1]} · 非本周") }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(byDay.getValue(day).inactive) { _, group ->
                    Surface(onClick = { inactiveDay = null; onSelect(group) }, color = MaterialTheme.colorScheme.surfaceVariant, shape = PocketShapes.Control) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(group.course.displayName, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("第${group.course.firstPeriod}–${group.course.lastPeriod}节 · ${courseWeeksSummary(group.weeks)}", color = Muted, fontSize = 12.sp, style = tabularTextStyle())
                            Text("查看各周地点与教师 ›", color = Blue, fontSize = 12.sp)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { inactiveDay = null }) { Text("完成") } })
    }
}

@Composable
private fun RoomsScreen(page: ClassroomPage, periods: List<Period>, semester: Semester,
                        api: SchoolApi, canQuery: Boolean, today: LocalDate, previewMode: Boolean,
                        onLogin: () -> Unit, onSessionExpired: () -> Unit, onSettings: () -> Unit, settingsEnabled: Boolean) {
    val dark = LocalPocketDark.current
    val scope = rememberCoroutineScope()
    val snapshotDate = LocalDate.of(2026, 10, 22)
    var dateText by rememberSaveable { mutableStateOf(if (canQuery || !previewMode) today.toString() else snapshotDate.toString()) }
    var selectingDate by rememberSaveable { mutableStateOf(false) }
    var previousToday by rememberSaveable { mutableStateOf(today.toString()) }
    var buildingName by rememberSaveable { mutableStateOf(StudyBuilding.MINGDE.name) }
    val building = StudyBuilding.valueOf(buildingName)
    var queryResult by remember { mutableStateOf<RoomQueryResult?>(null) }
    var queryError by remember { mutableStateOf<String?>(null) }
    var querying by remember { mutableStateOf(false) }
    LaunchedEffect(canQuery) {
        queryResult = null
        queryError = null
        if (canQuery) dateText = today.toString()
    }
    LaunchedEffect(today) {
        if (canQuery) dateText = followToday(LocalDate.parse(dateText), LocalDate.parse(previousToday), today).toString()
        previousToday = today.toString()
    }
    var first by rememberSaveable { mutableIntStateOf(1) }
    var last by rememberSaveable { mutableIntStateOf(2) }
    var noMarkOnly by rememberSaveable { mutableStateOf(true) }
    var search by rememberSaveable { mutableStateOf("") }
    val selectedDate = LocalDate.parse(dateText)
    val hasSnapshot = previewMode && !canQuery && selectedDate == snapshotDate && building == StudyBuilding.MINGDE
    val matchingResult = queryResult?.takeIf { it.date == selectedDate && it.building == building }
    val displayedRooms = matchingResult?.rooms ?: if (hasSnapshot) page.rows else emptyList()
    val filtered = remember(displayedRooms, search, noMarkOnly, first, last) {
        displayedRooms.filter {
            it.name.contains(search.trim(), ignoreCase = true) && (!noMarkOnly || it.status(first..last) == Occupancy.NO_MARK)
        }
    }
    Column(Modifier.fillMaxSize()) {
            PageHeading("教室", when {
                matchingResult != null -> "学校查询"
                hasSnapshot -> "历史样例"
                canQuery -> "查询说明"
                else -> "待连接"
            }, when {
                matchingResult != null -> "学校查询 · $dateText · ${building.title} · 已加载全部分页"
                canQuery -> "选择楼栋和日期，查询自习时段的占用情况"
                hasSnapshot -> "历史样例 · 2026年10月22日 · 仅第1页，非实时查询"
                else -> "登录学校账号后查询教室占用"
            } + "\n\n浅绿：无占用标记；蓝：有占用标记；灰：未知。\n无占用标记仅供自习选址参考，开放情况请以现场为准，学校状态含义仍待核对。\n\n查询需要有效的学校登录；已加载的结果不会自动更新。", onSettings, settingsEnabled)
      LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "rooms-filters", contentType = "filters") {
            PocketCard(shape = PocketShapes.Hero) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StudyBuilding.entries.forEach { candidate ->
                            FilterChip(selected = candidate == building, shape = PocketShapes.Compact, enabled = !querying,
                                onClick = { buildingName = candidate.name; queryError = null }, label = { Text(candidate.title) })
                        }
                    }
                    val colors = MaterialTheme.colorScheme
                    val divider = colors.outlineVariant.copy(alpha = 0.65f)
                    Surface(modifier = Modifier.fillMaxWidth(), color = colors.surfaceContainerLow, shape = PocketShapes.Control,
                        border = BorderStroke(0.8.dp, divider)) {
                        Column {
                            val dateColor = if (querying) colors.onSurface.copy(alpha = 0.38f) else colors.onSurface
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(enabled = !querying, role = Role.Button) { selectingDate = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.DateRange, null, Modifier.size(18.dp), tint = dateColor)
                                Column(Modifier.weight(1f)) {
                                    Text("查询日期", color = if (querying) dateColor else Muted, fontSize = 12.sp, lineHeight = 16.sp)
                                    Text(dateText, color = dateColor, fontSize = 16.sp, lineHeight = 20.sp,
                                        fontWeight = FontWeight.Medium, style = tabularTextStyle())
                                }
                                Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(20.dp), tint = dateColor)
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = divider)
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                PeriodMenu("开始节次", first, periods.filter { it.enabled }.map { it.number }, Modifier.weight(1f)) {
                                    first = it; if (last < it) last = it
                                }
                                VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 8.dp), thickness = 0.5.dp, color = divider)
                                PeriodMenu("结束节次", last, periods.filter { it.enabled && it.number >= first }.map { it.number }, Modifier.weight(1f)) { last = it }
                            }
                        }
                    }
                    TextField(value = search, onValueChange = { search = it }, singleLine = true, shape = PocketShapes.Control,
                        label = { Text("搜索已加载的教室") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(focusedContainerColor = colors.surfaceContainerLow,
                            unfocusedContainerColor = colors.surfaceContainerLow, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("只看无占用标记", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Switch(checked = noMarkOnly, onCheckedChange = { noMarkOnly = it })
                    }
                    Button(enabled = !querying, onClick = {
                        if (!canQuery) { onLogin(); return@Button }
                        if (semester.weekOn(selectedDate) !in 1..30) {
                            queryError = "请选择当前学期范围内的日期"
                            return@Button
                        }
                        scope.launch {
                            querying = true; queryError = null; queryResult = null
                            try { queryResult = api.rooms(semester, selectedDate, building) }
                            catch (error: CancellationException) { throw error }
                            catch (error: Exception) {
                                queryError = schoolErrorText(error)
                                if (error is SchoolLoginRequired) onSessionExpired()
                            }
                            finally { querying = false }
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = PocketShapes.Control) {
                        Text(if (querying) "正在加载全楼教室…" else if (canQuery) "查询${building.title}" else "连接学校后查询")
                    }
                    if (querying) LinearProgressIndicator(Modifier.fillMaxWidth())
                    queryError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("第 $first–$last 节", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), style = tabularTextStyle())
                Text(if (matchingResult != null) "已加载${matchingResult.rooms.size} / ${matchingResult.reportedTotal}条"
                    else if (hasSnapshot) "样例${page.rows.size} / 全楼${page.total}条" else "尚未查询", fontSize = 12.sp, color = Muted, style = tabularTextStyle())
            }
        }
        if (matchingResult == null && !hasSnapshot && !querying) item {
            EmptyCard(if (canQuery) "选择时段后查询" else "登录后查看${building.title}",
                if (canQuery) "选好楼栋、日期和节次，点击查询。" else "连接学校账号即可查询。", Icons.Outlined.Info)
        } else if (filtered.isEmpty() && !querying) item {
            EmptyCard("没有匹配的教室", if (hasSnapshot) "这里只筛选了10条历史样例。" else "请调整节次、搜索条件，或关闭仅看无占用标记。", Icons.Outlined.Search)
        }
        items(filtered, key = { it.code }, contentType = { "classroom" }) { room ->
            val state = remember(room, first, last) { room.status(first..last) }
            PocketCard() {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(room.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp)
                            Text("${room.type} · ${room.seats?.let { "$it 座" } ?: "座位数未知"}", fontSize = 12.sp, color = Muted, style = tabularTextStyle())
                        }
                        StatusPill(state)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        (1..11).forEach { number ->
                            val color = when (room.status(number)) {
                                Occupancy.NO_MARK -> if (dark) Color(0xFF1E463B) else Color(0xFFE2F3EE)
                                Occupancy.OCCUPIED -> if (dark) Color(0xFF263D69) else Color(0xFFDBE4FC)
                                Occupancy.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
                            }
                            Surface(color = color, shape = PocketShapes.Compact, modifier = Modifier.weight(1f)) {
                                Text(number.toString(), modifier = Modifier.padding(vertical = 6.dp), fontSize = 10.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Ink, style = tabularTextStyle())
                            }
                        }
                    }
                }
            }
        }
      }
    }
    if (selectingDate) PocketDatePicker(selectedDate, today, onDismiss = { selectingDate = false }) {
        dateText = it.toString()
        selectingDate = false
    }
}

@Composable
internal fun PeriodMenu(title: String, value: Int, options: List<Int>, modifier: Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = true }
            .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                Text("第 $value 节", fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, style = tabularTextStyle())
            }
            Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(20.dp), tint = Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { number -> DropdownMenuItem(text = { Text("第 $number 节", style = tabularTextStyle()) }, onClick = { onSelect(number); open = false }) }
        }
    }
}

@Composable
private fun StatusPill(state: Occupancy) {
    val (title, color) = when (state) {
        Occupancy.NO_MARK -> "无占用标记" to if (LocalPocketDark.current) Color(0xFF79D6B4) else Color(0xFF217A62)
        Occupancy.OCCUPIED -> "有占用标记" to Blue
        Occupancy.UNKNOWN -> "状态未知" to Muted
    }
    Surface(color = color.copy(alpha = 0.09f), shape = PocketShapes.Compact) {
        Text(title, color = color, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

@Composable
private fun ExamsScreen(exams: List<Exam>, now: LocalDateTime, schoolNotice: String? = null, onSettings: () -> Unit, settingsEnabled: Boolean) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Exam?>(null) }
    val filtered = remember(exams, filter, now) {
        exams.filter { exam -> when (filter) {
            1 -> exam.state(now) in listOf(ExamState.UPCOMING, ExamState.ONGOING)
            2 -> exam.state(now) == ExamState.UNARRANGED
            3 -> exam.state(now) == ExamState.ENDED
            else -> true
        } }.sortedWith(compareBy<Exam> { it.state(now) == ExamState.ENDED }.thenBy { it.date ?: LocalDate.MAX }.thenBy { it.start })
    }
    val next = remember(exams, now) { exams.filter { it.state(now) == ExamState.UPCOMING }.minByOrNull { it.date!!.atTime(it.start) } }
    Column(Modifier.fillMaxSize()) {
      PageHeading("考试", schoolStatus(schoolNotice), schoolNotice ?: "虚构考试样例，非本人考试安排，未连接学校账号。", onSettings, settingsEnabled)
      LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (next != null) item(key = "next-exam", contentType = "next-exam") {
            NextExamCard(next, now.toLocalDate(), demo = schoolNotice == null) { selected = next }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "待考试", "待安排", "已结束").forEachIndexed { index, title ->
                    FilterChip(selected = filter == index, shape = PocketShapes.Compact, onClick = { filter = index }, label = { Text(title) })
                }
            }
        }
        if (filtered.isEmpty()) item { EmptyCard("没有这一类考试", if (schoolNotice == null) "这里只展示虚构样例，不代表学校尚未发布考试。" else "本次已排考试列表中没有匹配记录；其他考试可能尚未安排。", Icons.AutoMirrored.Outlined.List) }
        itemsIndexed(filtered, key = { index, exam -> "${exam.id}#$index" }, contentType = { _, _ -> "exam" }) { _, exam ->
            PocketCard(onClick = { selected = exam }) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    val state = exam.state(now)
                    Text(when (state) { ExamState.UPCOMING -> "待考试"; ExamState.ONGOING -> "进行中"; ExamState.ENDED -> "已结束"; ExamState.UNARRANGED -> "时间待安排" },
                        color = if (state == ExamState.ENDED) Muted else Blue, fontSize = 12.sp)
                    Text(exam.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp)
                    Text(examTimeLabel(exam), fontSize = 14.sp, style = tabularTextStyle())
                    Text("${exam.room.ifBlank { "考场待定" }} · 座位${exam.seat ?: "未提供"}", fontSize = 13.sp, color = Muted, style = tabularTextStyle())
                }
            }
        }
    }
      }
    selected?.let { exam ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(exam.name) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (exam.batch.isNotBlank()) Text(exam.batch, style = tabularTextStyle())
                Text(examTimeLabel(exam), style = tabularTextStyle())
                Text("考场：${exam.room.ifBlank { "待定" }}")
                Text("座位：${exam.seat ?: "未提供"}", style = tabularTextStyle())
                exam.note?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted) }
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("完成") } })
    }
}

@Composable
private fun NextExamCard(exam: Exam, today: LocalDate, demo: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalPocketDark.current
    val immersive = LocalPocketImmersive.current
    val foreground = if (dark || immersive) colors.onPrimaryContainer else colors.onPrimary
    val badgeColor = if (dark) foreground.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.12f)
    val examDate = exam.date!!
    val daysLeft = ChronoUnit.DAYS.between(today, examDate)
    PocketCard(
        onClick = onClick,
        shape = PocketShapes.Hero,
        color = if (dark || immersive) colors.primaryContainer else colors.primary,
        border = if (immersive) pocketCardBorder() else if (dark) BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)) else null,
    ) {
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            if (!dark) {
                val stops = if (immersive) listOf(colors.primary.copy(alpha = 0.10f), Color.Transparent)
                    else listOf(colors.primary, lerp(colors.primary, Color.Black, 0.16f))
                Box(Modifier.matchParentSize().background(Brush.linearGradient(stops)))
            }
            Icon(Icons.Outlined.DateRange, contentDescription = null, tint = foreground.copy(alpha = 0.08f),
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 10.dp, y = 12.dp).size(88.dp).rotate(-12f))
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (demo) "下一场考试 · 示例" else "下一场考试",
                        modifier = Modifier.background(badgeColor, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
                        color = foreground, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                    Text(exam.name, color = foreground, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text("${examDate.format(ShortDate)}  ${exam.start}–${exam.end}", color = foreground,
                        fontSize = 14.sp, lineHeight = 20.sp, style = tabularTextStyle())
                    Text("${exam.room.ifBlank { "考场待定" }} · 座位${exam.seat ?: "未提供"}", color = foreground,
                        fontSize = 12.sp, lineHeight = 16.sp, style = tabularTextStyle())
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (daysLeft == 0L) "今天" else daysLeft.toString(), color = foreground, fontSize = 32.sp,
                        lineHeight = 38.sp, fontWeight = FontWeight.Bold, style = tabularTextStyle())
                    if (daysLeft > 0L) {
                        Text("天后", color = foreground, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(title: String, detail: String, icon: ImageVector) {
    PocketCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(25.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = Blue, modifier = Modifier.size(32.dp))
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(detail, fontSize = 12.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 18.sp)
        }
    }
}
