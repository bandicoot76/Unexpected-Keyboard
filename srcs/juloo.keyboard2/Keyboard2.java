package juloo.keyboard2;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.IBinder;
import android.text.InputType;
import android.util.Log;
import android.util.LogPrinter;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.dict.DictionariesActivity;
import juloo.keyboard2.dict.DictionarySwitcher;
import juloo.keyboard2.prefs.LayoutsPreference;
import juloo.keyboard2.suggestions.CandidatesView;
import juloo.keyboard2.suggestions.Suggestions;

public class Keyboard2 extends InputMethodService
  implements SharedPreferences.OnSharedPreferenceChangeListener
{
  /** The view containing the keyboard and candidates view. */
  private ViewGroup _keyboard_container_view;
  private Keyboard2View _keyboard_layout_view;
  private CandidatesView _candidates_view;
  private Suggestions _suggestions;
  private KeyEventHandler _keyeventhandler;
  /** If not 'null', the layout to use instead of [_config.current_layout]. */
  private KeyboardData _currentSpecialLayout;
  /** Layout associated with the currently selected locale. Not 'null'. */
  private KeyboardData _localeTextLayout;
  /** Installed and current locales. */
  private Dictionaries _dictionaries;
  private ViewGroup _emojiPane = null;
  private ViewGroup _clipboard_pane = null;
  private Handler _handler;
  private Receiver _recv;

  // Floating mode (Gboard-style): full-screen host, a drag/resize card.
  private FrameLayout _floatingHost = null;
  private FloatingKeyboardFrame _floatingFrame = null;
  private boolean _floating = false;

  private static final String PREF_FLOATING_X = "floating_x";
  private static final String PREF_FLOATING_Y = "floating_y";
  private static final String PREF_FLOATING_W = "floating_width";
  private static final String PREF_FLOATING_H = "floating_height_scale";
  private static final String PREF_FLOATING_ENABLED = "floating_enabled";

  /** One-handed mode: -1 = left hand (shrink right), 0 = off, +1 = right hand. */
  private int _oneHandedSide = 0;

  private Config _config;

  private FoldStateTracker _foldStateTracker;

  /** Layout currently visible before it has been modified. */
  KeyboardData current_layout_unmodified()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    KeyboardData layout = null;
    int layout_i = _config.get_current_layout();
    if (layout_i >= _config.layouts.size())
      layout_i = 0;
    if (layout_i < _config.layouts.size())
      layout = _config.layouts.get(layout_i);
    if (layout == null)
      layout = _localeTextLayout;
    return layout;
  }

  /** Layout currently visible. */
  KeyboardData current_layout()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    return LayoutModifier.modify_layout(current_layout_unmodified());
  }

  void setTextLayout(int l)
  {
    _config.set_current_layout(l);
    _currentSpecialLayout = null;
    // The active dictionary depends on the current layout.
    refresh_current_dictionary();
    refresh_candidates_view();
    _keyboard_layout_view.setKeyboard(current_layout());
  }

  void incrTextLayout(int delta)
  {
    int s = _config.layouts.size();
    setTextLayout((_config.get_current_layout() + delta + s) % s);
  }

  void setSpecialLayout(KeyboardData l)
  {
    _currentSpecialLayout = l;
    _keyboard_layout_view.setKeyboard(l);
  }

  KeyboardData loadLayout(int layout_id)
  {
    return KeyboardData.load(getResources(), layout_id);
  }

  /** Load a layout that contains a numpad. */
  KeyboardData loadNumpad(int layout_id)
  {
    return LayoutModifier.modify_numpad(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  KeyboardData loadNumericLayout()
  {
    return loadNumpad(_config.orientation_landscape ?
        R.xml.numeric_landscape : R.xml.numeric);
  }

  KeyboardData loadPinentry(int layout_id)
  {
    return LayoutModifier.modify_pinentry(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  @Override
  public void onCreate()
  {
    super.onCreate();
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    _handler = new Handler(getMainLooper());
    _foldStateTracker = new FoldStateTracker(this);
    _dictionaries = Dictionaries.instance(this);
    Config.initGlobalConfig(prefs, getResources(),
        _foldStateTracker.isUnfolded(), _dictionaries);
    _config = Config.globalConfig();
    _floating = prefs.getBoolean(PREF_FLOATING_ENABLED, false);
    _recv = this.new Receiver();
    _suggestions = new Suggestions(_recv, _config);
    _keyeventhandler = new KeyEventHandler(_config, _recv, _suggestions);
    KeyValue.Stateful._handler = _recv;
    _config.handler = _keyeventhandler;
    prefs.registerOnSharedPreferenceChangeListener(this);
    Logs.set_debug_logs(getResources().getBoolean(R.bool.debug_logs));
    refreshSubtypeImm();
    create_keyboard_view();
    ClipboardHistoryService.on_startup(this, _keyeventhandler);
    _foldStateTracker.setChangedCallback(() -> { refresh_config(); });
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    _foldStateTracker.close();
  }

  private void create_keyboard_view()
  {
    _keyboard_container_view = (ViewGroup)inflate_view(R.layout.keyboard);
    _keyboard_layout_view = (Keyboard2View)_keyboard_container_view.findViewById(R.id.keyboard_view);
    _candidates_view = (CandidatesView)_keyboard_container_view.findViewById(R.id.candidates_view);

    View menuBtn = _keyboard_container_view.findViewById(R.id.toolbar_menu_button);
    attach_menu_swipe(menuBtn);
    View voiceBtn = _keyboard_container_view.findViewById(R.id.toolbar_voice_button);
    voiceBtn.setOnClickListener((v) ->
        _recv.handle_event_key(KeyValue.Event.SWITCH_VOICE_TYPING));
    apply_one_handed();
  }

  /** Gboard-style menu button: tap = settings, swipe left/right = quick menus. */
  private void attach_menu_swipe(final View btn)
  {
    final float[] downX = new float[1];
    btn.setOnTouchListener(new View.OnTouchListener() {
      public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked())
        {
          case MotionEvent.ACTION_DOWN:
            downX[0] = e.getRawX();
            return false;
          case MotionEvent.ACTION_UP:
            float dx = e.getRawX() - downX[0];
            if (Math.abs(dx) >= dp(56f))
            {
              if (dx < 0) show_left_menu(); else show_right_menu();
              return true;
            }
            break;
        }
        return false;
      }
    });
    btn.setOnClickListener((v) -> show_toolbar_menu());
  }

  /** Swipe left: clipboard, emoji, one-handed, text editing. */
  private void show_left_menu()
  {
    show_list_menu(R.string.menu_title, new String[]{
        getString(R.string.menu_item_clipboard),
        getString(R.string.menu_item_emoji),
        getString(R.string.menu_item_one_handed),
        getString(R.string.menu_item_edit_text)
      }, new Runnable[]{
        () -> _recv.handle_event_key(KeyValue.Event.SWITCH_CLIPBOARD),
        () -> _recv.handle_event_key(KeyValue.Event.SWITCH_EMOJI),
        this::show_one_handed_menu,
        this::show_edit_menu
      });
  }

  /** Swipe right: floating, resize, undo, next language. */
  private void show_right_menu()
  {
    show_list_menu(R.string.menu_title, new String[]{
        getString(R.string.menu_item_floating),
        getString(R.string.menu_item_resize),
        getString(R.string.menu_item_undo),
        getString(R.string.menu_item_next_language)
      }, new Runnable[]{
        this::toggle_floating,
        this::show_resize_menu,
        () -> _keyeventhandler.key_up(KeyValue.getKeyByName("undo"), Pointers.Modifiers.EMPTY),
        () -> incrTextLayout(1)
      });
  }

  private void show_edit_menu()
  {
    show_list_menu(R.string.menu_item_edit_text, new String[]{
        "Cut", "Copy", "Paste", "Select all"
      }, new Runnable[]{
        () -> edit_key("cut"),
        () -> edit_key("copy"),
        () -> edit_key("paste"),
        () -> edit_key("selectAll")
      });
  }

  private void show_one_handed_menu()
  {
    show_list_menu(R.string.menu_item_one_handed, new String[]{
        "Left hand", "Right hand", "Off"
      }, new Runnable[]{
        () -> set_one_handed(-1),
        () -> set_one_handed(1),
        () -> set_one_handed(0)
      });
  }

  private void show_resize_menu()
  {
    show_list_menu(R.string.menu_item_resize, new String[]{
        "Wider", "Narrower", "Taller", "Shorter"
      }, new Runnable[]{
        () -> resize_floating(true, false),
        () -> resize_floating(false, false),
        () -> resize_floating(false, true),
        () -> resize_floating(false, false)
      });
  }

  private void resize_floating(boolean wider, boolean taller)
  {
    if (!_floating)
      toggle_floating();
    ensure_floating_frame();
    if (wider || !taller)
    {
      int delta = wider ? (int)dp(24f) : -(int)dp(24f);
      _floatingFrame.adjustWidth(delta);
    }
    if (taller)
      _floatingFrame.adjustHeightScale(+0.08f);
    else if (!wider)
      _floatingFrame.adjustHeightScale(0f); // narrower handled above; shorter via width
  }

  private void edit_key(String name)
  {
    _keyeventhandler.key_up(KeyValue.getKeyByName(name), Pointers.Modifiers.EMPTY);
  }

  private void set_one_handed(int side)
  {
    _oneHandedSide = side;
    apply_one_handed();
  }

  private void apply_one_handed()
  {
    if (_keyboard_container_view == null)
      return;
    if (_oneHandedSide == 0)
    {
      _keyboard_container_view.setPadding(0, 0, 0, 0);
      return;
    }
    int inset = Math.round(getResources().getDisplayMetrics().widthPixels * 0.28f);
    if (_oneHandedSide < 0)
      _keyboard_container_view.setPadding(0, 0, inset, 0); // left hand: shrink right
    else
      _keyboard_container_view.setPadding(inset, 0, 0, 0); // right hand: shrink left
  }

  private void show_list_menu(int titleRes, String[] items, Runnable[] actions)
  {
    ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
        android.R.layout.simple_list_item_1, items);
    final AlertDialog dialog = new AlertDialog.Builder(this)
      .setTitle(titleRes)
      .setAdapter(adapter, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface _d, int which) {
          if (which >= 0 && which < actions.length && actions[which] != null)
            actions[which].run();
        }
      })
      .create();
    Utils.show_dialog_on_ime(dialog, this);
  }

  /** Gboard-style top-left menu: floating, emoji, clipboard, settings. */
  private void show_toolbar_menu()
  {
    final java.util.List<String> items = Arrays.asList(
        getString(R.string.menu_item_floating),
        getString(R.string.menu_item_emoji),
        getString(R.string.menu_item_clipboard),
        getString(R.string.menu_item_settings));
    ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
        android.R.layout.simple_list_item_1, items);
    final AlertDialog dialog = new AlertDialog.Builder(this)
      .setTitle(R.string.menu_title)
      .setAdapter(adapter, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface _d, int which) {
          switch (which)
          {
            case 0: toggle_floating(); break;
            case 1: _recv.handle_event_key(KeyValue.Event.SWITCH_EMOJI); break;
            case 2: _recv.handle_event_key(KeyValue.Event.SWITCH_CLIPBOARD); break;
            case 3: start_activity(SettingsActivity.class); break;
          }
        }
      })
      .create();
    Utils.show_dialog_on_ime(dialog, this);
  }

  InputMethodManager get_imm()
  {
    return (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
  }

  private void refreshSubtypeImm()
  {
    _config.shouldOfferVoiceTyping = true;
    KeyboardData default_layout = null;
    _config.device_locales = DeviceLocales.load(this);
    if (_config.device_locales.default_ != null)
    {
      String layout_name = _config.device_locales.default_.default_layout;
      if (layout_name != null)
        default_layout = LayoutsPreference.layout_of_string(getResources(), layout_name);
    }
    _config.extra_keys_subtype = _config.device_locales.extra_keys();
    if (default_layout == null)
      default_layout = loadLayout(R.xml.latn_qwerty_us);
    _localeTextLayout = default_layout;
  }

  private void refresh_current_dictionary()
  {
    _config.should_show_dictionary_switch =
      (_config.device_locales.installed.size() > 0);
    String dict_name = _dictionaries.get_selected(_config);
    if (dict_name == null)
      dict_name = (_config.device_locales.default_ != null) ?
        _config.device_locales.default_.dictionary : null;
    _dictionaries.set_current_dictionary(_config, dict_name);
  }

  /** Remember and apply the dictionary chosen by the user for the current
      context. */
  private void select_dictionary(String dict_name)
  {
    _dictionaries.set_selected(_config, dict_name);
    refresh_current_dictionary();
    refresh_candidates_view();
  }

  private void refresh_candidates_view()
  {
    boolean should_show =
      _config.suggestions_enabled
      && _config.editor_config.should_show_candidates_view
      && !_config.split_layout;
    if (should_show)
    {
      _candidates_view.refresh_config(_config);
      _keyeventhandler.dictionary_changed();
    }
    _candidates_view.setVisibility(should_show ? View.VISIBLE : View.GONE);
  }

  /** Might re-create the keyboard view. [_keyboard_layout_view.setKeyboard()] and
      [setInputView()] must be called soon after. */
  private void refresh_config()
  {
    int prev_theme = _config.theme;
    _config.refresh(getResources(), _foldStateTracker.isUnfolded(), _dictionaries);
    refresh_current_dictionary();
    // Refreshing the theme config requires re-creating the views
    if (prev_theme != _config.theme)
    {
      create_keyboard_view();
      _emojiPane = null;
      _clipboard_pane = null;
      setInputView(_keyboard_container_view);
    }
    // Set keyboard background opacity
    Drawable bg = _keyboard_container_view.getBackground().mutate();
    bg.setAlpha(_config.keyboardOpacity);
    _keyboard_container_view.setBackground(bg);
    _keyboard_layout_view.reset();
    refresh_candidates_view();
  }

  private KeyboardData refresh_special_layout()
  {
    if (_config.editor_config.numeric_layout)
    {
      switch (_config.selected_number_layout)
      {
        case PIN:
          return loadPinentry(_config.orientation_landscape ?
              R.xml.pin_landscape : R.xml.pin);
        case NUMBER:
          return loadNumericLayout();
      }
    }
    return null;
  }

  @Override
  public void onStartInputView(EditorInfo info, boolean restarting)
  {
    _config.editor_config.refresh(info, getResources());
    refresh_config();
    _currentSpecialLayout = refresh_special_layout();
    _keyboard_layout_view.setKeyboard(current_layout());
    _keyeventhandler.started();
    setInputView(_keyboard_container_view);
    Logs.debug_startup_input_view(info, _config);
  }

  @Override
  public void setExtractViewShown(boolean shown){
      super.setExtractViewShown(false);
  }

  @Override
  public void setInputView(View v)
  {
    ViewParent parent = v.getParent();
    if (parent != null && parent instanceof ViewGroup)
      ((ViewGroup)parent).removeView(v);

    if (_floating && v == _keyboard_container_view)
    {
      ensure_floating_frame();
      _floatingFrame.setKeyboardContainer(_keyboard_container_view);
      _floatingFrame.setKeyboardView(_keyboard_layout_view);
      restore_floating_geometry();
      super.setInputView(_floatingHost);
      // The IMS adds the input view at MATCH_PARENT x WRAP_CONTENT, which
      // collapses the floating host to the card's own height and zeroes the
      // card's vertical travel. Force the host full-screen so the card has
      // room to be dragged anywhere and touches reach it wherever it parks.
      ViewGroup.LayoutParams hostLp = _floatingHost.getLayoutParams();
      if (hostLp != null)
      {
        hostLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        hostLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        _floatingHost.setLayoutParams(hostLp);
      }
      updateSoftInputWindowLayoutParams();
      _floatingHost.requestApplyInsets();
      return;
    }

    super.setInputView(v);
    updateSoftInputWindowLayoutParams();
    v.requestApplyInsets();
  }

  @Override
  public void updateFullscreenMode() {
    super.updateFullscreenMode();
    updateSoftInputWindowLayoutParams();
  }

  private void updateSoftInputWindowLayoutParams() {
    final Window window = getWindow().getWindow();
    // On API >= 35, Keyboard2View behaves as edge-to-edge
    // APIs 30 to 34 have visual artifact when edge-to-edge is enabled
    if (VERSION.SDK_INT >= 35)
    {
      WindowManager.LayoutParams wattrs = window.getAttributes();
      wattrs.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
      // Allow to draw behind system bars
      wattrs.setFitInsetsTypes(0);
      window.setDecorFitsSystemWindows(false);
    }
    updateLayoutHeightOf(window, ViewGroup.LayoutParams.MATCH_PARENT);
    final View inputArea = window.findViewById(android.R.id.inputArea);

    updateLayoutHeightOf(
            (View) inputArea.getParent(),
            (isFullscreenMode() || _floating)
                    ? ViewGroup.LayoutParams.MATCH_PARENT
                    : ViewGroup.LayoutParams.WRAP_CONTENT);
    updateLayoutGravityOf((View) inputArea.getParent(), Gravity.BOTTOM);

  }

  // ----- Floating mode (Gboard-style card: drag + resize) -----

  private void ensure_floating_frame()
  {
    if (_floatingFrame != null)
      return;
    _floatingHost = new FrameLayout(this);
    _floatingFrame = new FloatingKeyboardFrame(this);
    _floatingFrame.setListener(this::on_floating_geometry);
    // The host must fill the input area so the card has room to travel.
    // A WRAP_CONTENT host collapses to the card size, leaving maxX/maxY at 0
    // and pinning the card to the top-left where it cannot be dragged.
    _floatingHost.addView(_floatingFrame, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.TOP | Gravity.START));
  }

  private void restore_floating_geometry()
  {
    if (_floatingFrame == null)
      return;
    SharedPreferences prefs = Config.globalPrefs();
    int w = prefs.getInt(PREF_FLOATING_W, -1);
    if (w <= 0)
      w = Math.round(getResources().getDisplayMetrics().widthPixels * 0.7f);
    // Park centred and about a quarter down so the card is neither hidden
    // under the status bar nor stuck knee-height off a fresh install.
    int x = prefs.getInt(PREF_FLOATING_X,
        Math.max(0, (getResources().getDisplayMetrics().widthPixels - w) / 2));
    int y = prefs.getInt(PREF_FLOATING_Y,
        getResources().getDisplayMetrics().heightPixels / 4);
    float h = prefs.getFloat(PREF_FLOATING_H, 1f);
    _floatingFrame.setInitialGeometry(x, y, w, h);
  }

  private void on_floating_geometry(int x, int y, int w, float h, boolean committed)
  {
    if (!committed)
      return;
    SharedPreferences.Editor e = Config.globalPrefs().edit();
    e.putInt(PREF_FLOATING_X, x);
    e.putInt(PREF_FLOATING_Y, y);
    e.putInt(PREF_FLOATING_W, w);
    e.putFloat(PREF_FLOATING_H, h);
    e.apply();
  }

  private void toggle_floating()
  {
    _floating = !_floating;
    Config.globalPrefs().edit().putBoolean(PREF_FLOATING_ENABLED, _floating).apply();
    if (_floating)
    {
      setInputView(_keyboard_container_view);
    }
    else
    {
      _keyboard_layout_view.setRowHeightScale(1f);
      super.setInputView(_keyboard_container_view);
      updateSoftInputWindowLayoutParams();
      _keyboard_container_view.requestApplyInsets();
    }
  }

  @Override
  public void onComputeInsets(InputMethodService.Insets outInsets)
  {
    super.onComputeInsets(outInsets);
    if (!_floating || _floatingFrame == null)
      return;
    Rect r = _floatingFrame.screenRect();
    // Floating card: report only the touchable region and reserve no vertical
    // space, so the card floats over the app instead of pushing it up.
    // Reporting visibleTopInsets/contentTopInsets from the card's screen
    // rect is a feedback loop (the system re-places the IME window, which
    // moves the card, which changes the insets) — it was what made the card
    // jump to the top and refuse to stay put.
    outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
    outInsets.touchableRegion.set(r);
    outInsets.visibleTopInsets = 0;
    outInsets.contentTopInsets = 0;
  }

  private static void updateLayoutHeightOf(final Window window, final int layoutHeight) {
    final WindowManager.LayoutParams params = window.getAttributes();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      window.setAttributes(params);
    }
  }

  private static void updateLayoutHeightOf(final View view, final int layoutHeight) {
    final ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      view.setLayoutParams(params);
    }
  }

  private static void updateLayoutGravityOf(final View view, final int layoutGravity) {
    final ViewGroup.LayoutParams lp = view.getLayoutParams();
    if (lp instanceof LinearLayout.LayoutParams) {
      final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    } else if (lp instanceof FrameLayout.LayoutParams) {
      final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    }
  }

  @Override
  public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype)
  {
    refreshSubtypeImm();
    refresh_current_dictionary();
    refresh_candidates_view();
    _keyboard_layout_view.setKeyboard(current_layout());
  }

  @Override
  public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd)
  {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
    _keyeventhandler.selection_updated(oldSelStart, newSelStart, newSelEnd);
    if ((oldSelStart == oldSelEnd) != (newSelStart == newSelEnd))
      _keyboard_layout_view.set_selection_state(newSelStart != newSelEnd);
  }

  @Override
  public void onFinishInputView(boolean finishingInput)
  {
    super.onFinishInputView(finishingInput);
    _keyboard_layout_view.reset();
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences _prefs, String _key)
  {
    refresh_config();
    _keyboard_layout_view.setKeyboard(current_layout());
  }

  @Override
  public boolean onEvaluateFullscreenMode()
  {
    /* Entirely disable fullscreen mode. */
    return false;
  }

  @Override
  public boolean onEvaluateInputViewShown()
  {
    // Since Android 16, this method returns [false] for unknown reasons.
    if (super.onEvaluateInputViewShown())
      return true;
    if (getResources().getConfiguration().hardKeyboardHidden
        == Configuration.HARDKEYBOARDHIDDEN_NO
        && _config.physical_keyboard_hide)
    {
      Logs.debug("Physical keyboard is present");
      return false;
    }
    return true;
  }

  public void launch_dictionaries_activity()
  {
    start_activity(DictionariesActivity.class);
  }

  /** Called from [onClick] attributes. */
  public void launch_dictionaries_activity(View v)
  {
    launch_dictionaries_activity();
  }

  void start_activity(Class cls)
  {
    Intent intent = new Intent(this, cls);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(intent);
  }

  /** Not static */
  public class Receiver implements KeyEventHandler.IReceiver,
         KeyValue.Stateful.Symbol_provider, DictionarySwitcher.Callback
  {
    public void handle_event_key(KeyValue.Event ev)
    {
      switch (ev)
      {
        case CONFIG:
          start_activity(SettingsActivity.class);
          break;

        case SWITCH_TEXT:
          _currentSpecialLayout = null;
          _keyboard_layout_view.setKeyboard(current_layout());
          break;

        case SWITCH_NUMERIC:
          setSpecialLayout(loadNumericLayout());
          break;

        case SWITCH_EMOJI:
          if (_emojiPane == null)
            _emojiPane = (ViewGroup)inflate_view(R.layout.emoji_pane);
          setInputView(_emojiPane);
          break;

        case SWITCH_CLIPBOARD:
          if (_clipboard_pane == null)
            _clipboard_pane = (ViewGroup)inflate_view(R.layout.clipboard_pane);
          setInputView(_clipboard_pane);
          break;

        case SWITCH_BACK_EMOJI:
        case SWITCH_BACK_CLIPBOARD:
          setInputView(_keyboard_container_view);
          break;

        case CHANGE_METHOD_PICKER:
          get_imm().showInputMethodPicker();
          break;

        case CHANGE_METHOD_PREV:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToLastInputMethod(getConnectionToken());
          else
            switchToPreviousInputMethod();
          break;

        case CHANGE_METHOD_NEXT:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToNextInputMethod(getConnectionToken(), false);
          else
            switchToNextInputMethod(false);
          break;

        case ACTION:
          InputConnection conn = getCurrentInputConnection();
          if (conn != null)
            conn.performEditorAction(_config.editor_config.actionId);
          break;

        case SWITCH_FORWARD:
          incrTextLayout(1);
          break;

        case SWITCH_BACKWARD:
          incrTextLayout(-1);
          break;

        case SWITCH_GREEKMATH:
          setSpecialLayout(loadNumpad(R.xml.greekmath));
          break;

        case CAPS_LOCK:
          set_shift_state(true, true);
          break;

        case SWITCH_VOICE_TYPING:
          if (!VoiceImeSwitcher.switch_to_voice_ime(Keyboard2.this, get_imm(),
                Config.globalPrefs()))
            _config.shouldOfferVoiceTyping = false;
          break;

        case SWITCH_VOICE_TYPING_CHOOSER:
          VoiceImeSwitcher.choose_voice_ime(Keyboard2.this, get_imm(),
              Config.globalPrefs());
          break;

        case HIDE_SELF:
          Keyboard2.this.requestHideSelf(0);
          break;

        case CHANGE_DICTIONARY:
          new DictionarySwitcher(Keyboard2.this, _dictionaries, this).choose();
          break;

        case TOGGLE_FLOATING:
          Keyboard2.this.toggle_floating();
          break;
      }
    }

    public void set_shift_state(boolean state, boolean lock)
    {
      _keyboard_layout_view.set_shift_state(state, lock);
    }

    public void set_compose_pending(boolean pending)
    {
      _keyboard_layout_view.set_compose_pending(pending);
    }

    public void selection_state_changed(boolean selection_is_ongoing)
    {
      _keyboard_layout_view.set_selection_state(selection_is_ongoing);
    }

    public InputConnection getCurrentInputConnection()
    {
      return Keyboard2.this.getCurrentInputConnection();
    }

    public Handler getHandler()
    {
      return _handler;
    }

    public void set_suggestions(Suggestions suggestions)
    {
      _candidates_view.set_candidates(suggestions);
    }

    public String provide_stateful_key_symbol(KeyValue.Stateful q)
    {
      switch (q)
      {
        case Complete_first: return _suggestions.suggestions[0];
        case Complete_second: return _suggestions.suggestions[1];
        case Complete_third: return _suggestions.suggestions[2];
        case Complete_emoji: return _suggestions.emoji_suggestion;
      }
      return "";
    }

    public void on_change_dictionary(String dict_name)
    {
      select_dictionary(dict_name);
    }

    public void launch_dictionaries_activity()
    {
      Keyboard2.this.launch_dictionaries_activity();
    }
  }

  private IBinder getConnectionToken()
  {
    return getWindow().getWindow().getAttributes().token;
  }

  private View inflate_view(int layout)
  {
    return View.inflate(new ContextThemeWrapper(this, _config.theme), layout, null);
  }

  private float dp(float v)
  {
    return android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, v,
        getResources().getDisplayMetrics());
  }
}
