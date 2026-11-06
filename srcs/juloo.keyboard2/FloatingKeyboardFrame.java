package juloo.keyboard2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * The card an IME shows its keyboard in while floating: a drag handle along
 * the top and a resize grip on the bottom-right corner, parked wherever the
 * user last left it.
 *
 * <p>The frame owns none of the keyboard itself. The keyboard container (the
 * candidates strip plus {@link Keyboard2View}) is added unchanged and measured
 * by the normal layout pass against the card's width. The card's width is a
 * share of the screen; the keyboard's row height is scaled by a multiplier the
 * grip writes, so widening the card and making its rows taller are the two
 * halves of one corner.</p>
 *
 * <p>Position is expressed as a translation from the top-left of the IME
 * input area, which in floating mode is laid out full-screen (see
 * {@code Keyboard2#updateSoftInputWindowLayoutParams}), so {@code setX}/{@code setY}
 * move the card freely over the app behind it.</p>
 */
public class FloatingKeyboardFrame extends FrameLayout
{
  /** The strip above the keys the card is dragged by. */
  private static final float HANDLE_H = 22f;

  /** The corner the card is resized from, the same 36dp thumb zone the pill above it. */
  private static final float GRIP_SIZE = 36f;

  /** Narrowest the card may be dragged. */
  private static final float MIN_WIDTH = 240f;

  /** Row-height multiplier bounds. */
  private static final float MIN_HEIGHT_SCALE = 0.6f;
  private static final float MAX_HEIGHT_SCALE = 2.4f;

  /** How close (in dp) the card's bottom edge must be to the screen bottom to
      auto-dock on drop. The user asked for ~1 cm. */
  private static final float DOCK_MARGIN_DP = 40f;

  /** Max gap between two taps (ms) on the handle counted as a dock double-tap. */
  private static final int DOUBLE_TAP_MS = 320;

  /** Where the frame ended up or was resized to. */
  public interface Listener
  {
    void onGeometryChanged(int xPx, int yPx, int widthPx, float heightScale,
        boolean committed);
  }

  /** The keyboard view the grip's row-height multiplier is pushed into. */
  private Keyboard2View _keyboardView;

  private final int _handlePx;
  private final int _gripPx;
  private final int _topGuardPx;

  private int _widthPx;
  private float _heightScale = 1f;
  private float _xPx;
  private float _yPx;

  // Drag state.
  private float _downRawX;
  private float _downRawY;
  private float _startX;
  private float _startY;
  private boolean _dragging;

  // Resize state.
  private int _startWidthPx;
  private int _startCardHeightPx;
  private float _startHeightScale;
  private boolean _resizing;

  private Listener _listener;

  /** Invoked when the user asks to dock (drag-to-bottom or double-tap). */
  private Runnable _dockAction;

  private long _lastTapUpTime = 0;

  private final Paint _chromePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  public FloatingKeyboardFrame(Context context)
  {
    super(context);
    _handlePx = (int)dp(HANDLE_H);
    _gripPx = (int)dp(GRIP_SIZE);
    _topGuardPx = (int)dp(48f);
    setClickable(true);
    setBackground(cardBackground());
    setElevation(dp(8f));
    setClipChildren(false);
    setClipToPadding(false);
    _chromePaint.setStyle(Paint.Style.FILL);
    _chromePaint.setColor(0x66808080);
  }

  public void setKeyboardView(Keyboard2View v)
  {
    _keyboardView = v;
  }

  public void setListener(Listener l)
  {
    _listener = l;
  }

  /** Sets the callback that re-docks the keyboard into the bottom bar. */
  public void setDockAction(Runnable r)
  {
    _dockAction = r;
  }

  public int widthPx() { return _widthPx; }

  public float heightScale() { return _heightScale; }

  public int positionXPx() { return Math.round(_xPx); }

  public int positionYPx() { return Math.round(_yPx); }

  /** Hosts the keyboard container (candidates + keys), replacing any prior child. */
  public void setKeyboardContainer(View container)
  {
    removeAllViews();
    addView(container, new LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
  }

  /** Restores the card after a rebuild, or sets its first parked position. */
  public void setInitialGeometry(int xPx, int yPx, int widthPx, float heightScale)
  {
    _xPx = xPx;
    _yPx = yPx;
    _widthPx = Math.max(_gripPx * 3, widthPx);
    _heightScale = clampHeightScale(heightScale);
    applyHeightScale();
    seatPosition();
    requestLayout();
    invalidate();
  }

  /** Nudges the card width (resize menu), keeping the card within the screen. */
  public void adjustWidth(int deltaPx)
  {
    View parent = (View)getParent();
    android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
    int maxWidth = (parent != null && parent.getWidth() > 0) ? parent.getWidth() : dm.widthPixels;
    int minWidth = (int)Math.min(dp(MIN_WIDTH), maxWidth);
    _widthPx = (int)clamp(_widthPx + deltaPx, minWidth, maxWidth);
    requestLayout();
    invalidate();
    notifyGeometry(true);
  }

  /** Nudges the row height (resize menu). */
  public void adjustHeightScale(float delta)
  {
    _heightScale = clampHeightScale(_heightScale + delta);
    applyHeightScale();
    requestLayout();
    invalidate();
    notifyGeometry(true);
  }

  /** The card's bounds on screen, for the touch insets. */
  public Rect screenRect()
  {
    // getLocationOnScreen already accounts for the card's translation.
    int[] loc = new int[2];
    getLocationOnScreen(loc);
    return new Rect(loc[0], loc[1], loc[0] + getWidth(), loc[1] + getHeight());
  }

  private void applyHeightScale()
  {
    if (_keyboardView != null)
      _keyboardView.setRowHeightScale(_heightScale);
  }

  private void applyPosition(float x, float y)
  {
    android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
    // Authoritative travel bounds are the screen (the floating host fills it).
    // Prefer a fully-measured parent when available, but never let an
    // unmeasured or collapsed parent clamp travel to zero.
    View parent = (View)getParent();
    float boundW = (parent != null && parent.getWidth() > _widthPx)
        ? parent.getWidth() : dm.widthPixels;
    float boundH = (parent != null && parent.getHeight() > 0)
        ? parent.getHeight() : dm.heightPixels;
    int cardH = getHeight() > 0 ? getHeight()
        : (_handlePx + childKeyboardHeight());
    float maxX = Math.max(0f, boundW - _widthPx);
    float maxY = Math.max(0f, boundH - cardH);
    // Write the clamped value back so a drag that hits the screen edge does
    // not persist the out-of-bounds coordinate (which is what re-parked the
    // card at the top on the next open). Also keep the card below the status
    // bar, where the drag handle is actually reachable.
    _xPx = clamp(x, 0f, maxX);
    _yPx = clamp(y, _topGuardPx, maxY);
    setTranslationX(_xPx);
    setTranslationY(_yPx);
  }

  /** Re-seats inside the parent's current bounds. */
  private void seatPosition()
  {
    applyPosition(_xPx, _yPx);
  }

  private int childKeyboardHeight()
  {
    View child = getChildAt(0);
    return child == null ? 0 : child.getMeasuredHeight();
  }

  private boolean inHandleZone(float x, float y)
  {
    return y >= 0f && y <= _handlePx;
  }

  private boolean inGripZone(float x, float y)
  {
    return x >= getWidth() - _gripPx && x <= getWidth()
        && y >= getHeight() - _gripPx && y <= getHeight();
  }

  @Override
  protected void onMeasure(int wSpec, int hSpec)
  {
    View child = getChildAt(0);
    if (child == null)
    {
      setMeasuredDimension(_widthPx, _handlePx);
      return;
    }
    int childWSpec = MeasureSpec.makeMeasureSpec(_widthPx, MeasureSpec.EXACTLY);
    int childHSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
    child.measure(childWSpec, childHSpec);
    setMeasuredDimension(_widthPx, _handlePx + child.getMeasuredHeight());
    seatPosition();
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom)
  {
    View child = getChildAt(0);
    if (child != null)
      child.layout(0, _handlePx, getWidth(), _handlePx + child.getMeasuredHeight());
    seatPosition();
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent e)
  {
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN
        && inGripZone(e.getX(), e.getY()))
      return true;
    return super.onInterceptTouchEvent(e);
  }

  @Override
  public boolean onTouchEvent(MotionEvent e)
  {
    switch (e.getActionMasked())
    {
      case MotionEvent.ACTION_DOWN:
        if (inGripZone(e.getX(), e.getY()))
        {
          beginResize(e);
          return true;
        }
        if (inHandleZone(e.getX(), e.getY()))
        {
          long now = android.os.SystemClock.uptimeMillis();
          if (now - _lastTapUpTime <= DOUBLE_TAP_MS)
          {
            _lastTapUpTime = 0;
            requestDock();
            return true;
          }
          beginDrag(e);
          return true;
        }
        break;
      case MotionEvent.ACTION_MOVE:
        if (_resizing) { doResize(e, false); return true; }
        if (_dragging) { doDrag(e, false); return true; }
        break;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (_resizing) { doResize(e, true); _resizing = false; return true; }
        if (_dragging) { doDrag(e, true); _dragging = false; return true; }
        break;
      default: break;
    }
    return super.onTouchEvent(e);
  }

  private void beginDrag(MotionEvent e)
  {
    _dragging = true;
    _downRawX = e.getRawX();
    _downRawY = e.getRawY();
    _startX = _xPx;
    _startY = _yPx;
  }

  private void doDrag(MotionEvent e, boolean committed)
  {
    boolean wasDrag = _dragging;
    _xPx = _startX + (e.getRawX() - _downRawX);
    _yPx = _startY + (e.getRawY() - _downRawY);
    seatPosition();
    if (committed)
    {
      // Remember the up so a quick second tap can count as a double-tap dock.
      if (Math.hypot(e.getRawX() - _downRawX, e.getRawY() - _downRawY) < dp(8f))
        _lastTapUpTime = android.os.SystemClock.uptimeMillis();
      if (nearBottom())
        requestDock();
      else
        notifyGeometry(true);
    }
  }

  /** True when the card's bottom edge is within ~1 cm of the screen bottom. */
  private boolean nearBottom()
  {
    android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
    View parent = (View)getParent();
    float boundH = (parent != null && parent.getHeight() > 0)
        ? parent.getHeight() : dm.heightPixels;
    int cardH = getHeight() > 0 ? getHeight()
        : (_handlePx + childKeyboardHeight());
    return (boundH - (_yPx + cardH)) <= dp(DOCK_MARGIN_DP);
  }

  private void requestDock()
  {
    _lastTapUpTime = 0;
    if (_dockAction != null)
      _dockAction.run();
  }

  /** Drag the card by raw deltas without touching the stored position. */
  private void moveBy(float dx, float dy)
  {
    _xPx += dx;
    _yPx += dy;
    seatPosition();
  }

  private void beginResize(MotionEvent e)
  {
    _resizing = true;
    _downRawX = e.getRawX();
    _downRawY = e.getRawY();
    _startWidthPx = _widthPx;
    _startCardHeightPx = Math.max(1, getHeight());
    _startHeightScale = _heightScale;
  }

  private void doResize(MotionEvent e, boolean committed)
  {
    View parent = (View)getParent();
    int maxWidth = parent == null ? _widthPx : parent.getWidth();
    int minWidth = (int)Math.min(dp(MIN_WIDTH), maxWidth);
    _widthPx = (int)clamp(_startWidthPx + (e.getRawX() - _downRawX), minWidth, maxWidth);
    float dyScale = (_startCardHeightPx + (e.getRawY() - _downRawY))
        / (float)_startCardHeightPx;
    _heightScale = clampHeightScale(_startHeightScale * dyScale);
    applyHeightScale();
    requestLayout();
    notifyGeometry(committed);
  }

  private void notifyGeometry(boolean committed)
  {
    if (_listener != null)
      _listener.onGeometryChanged(Math.round(_xPx), Math.round(_yPx),
          _widthPx, _heightScale, committed);
  }

  private float clampHeightScale(float s)
  {
    return clamp(s, MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE);
  }

  private static float clamp(float v, float min, float max)
  {
    return Math.max(min, Math.min(max, v));
  }

  private GradientDrawable cardBackground()
  {
    TypedValue tv = new TypedValue();
    int color = 0xFF1B1B1B;
    if (getContext().getTheme().resolveAttribute(R.attr.colorKeyboard, tv, true))
      color = tv.data;
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(10f));
    d.setStroke((int)dp(1f), 0x33808080);
    return d;
  }

  @Override
  protected void dispatchDraw(Canvas canvas)
  {
    super.dispatchDraw(canvas);

    // Drag pill: a centred rounded strip along the top.
    float pillW = dp(44f);
    float pillH = dp(4f);
    float pillX = (getWidth() - pillW) / 2f;
    float pillY = (_handlePx - pillH) / 2f;
    canvas.drawRoundRect(pillX, pillY, pillX + pillW, pillY + pillH, pillH / 2, pillH / 2,
        _chromePaint);

    // Resize grip: two diagonals across the bottom-left edge of the corner.
    float inset = dp(GRIP_SIZE) * 0.32f;
    float right = getWidth() - inset;
    float bottom = getHeight() - inset;
    float longLen = dp(13f);
    float shortLen = dp(7f);
    _chromePaint.setStyle(Paint.Style.STROKE);
    _chromePaint.setStrokeWidth(dp(2f));
    _chromePaint.setStrokeCap(Paint.Cap.ROUND);
    canvas.drawLine(right, bottom - longLen, right - longLen, bottom, _chromePaint);
    canvas.drawLine(right, bottom - shortLen, right - shortLen, bottom, _chromePaint);
    _chromePaint.setStyle(Paint.Style.FILL);
  }

  private float dp(float v)
  {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
        getResources().getDisplayMetrics());
  }
}