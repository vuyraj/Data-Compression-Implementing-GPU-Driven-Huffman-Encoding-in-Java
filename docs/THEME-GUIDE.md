# DataComp Theme Guide

## Available Themes

DataComp now supports both **Dark** and **Light** themes for better user experience and accessibility.

### Dark Theme (Default)
- Modern dark color scheme with blue accents
- Optimized for low-light environments
- Reduces eye strain during extended use
- Features:
  - Background: Dark navy (#1a1b26)
  - Primary accent: Blue (#7aa2f7)
  - Cards: Dark gray (#24283b)
  - Text: Light gray (#c0caf5)

### Light Theme
- Clean, professional light color scheme
- Better for bright environments
- Improved readability in daylight
- Features:
  - Background: Light gray (#f5f5f5)
  - Primary accent: Material Blue (#1976d2)
  - Cards: White (#ffffff)
  - Text: Dark gray (#212121)

## How to Switch Themes

### Using the GUI

1. **Open Settings**
   - Click the "Settings" button in the navigation bar
   
2. **Select Theme**
   - In the "UI Settings" section, find the "Theme" dropdown
   - Choose between "Dark" or "Light"
   
3. **Apply Changes**
   - Click the "Save Settings" button
   - The theme will be applied immediately to the entire application

### Default Theme

The default theme is set in `application.conf`:

```hocon
ui {
    # Theme: "dark", "light"
    theme = "dark"
}
```

You can change the default by editing this configuration file.

## Theme Files

- **Dark Theme**: `/app/src/main/resources/css/dark-theme.css`
- **Light Theme**: `/app/src/main/resources/css/light-theme.css`

## Theme Customization

Both themes use consistent CSS class names, making it easy to customize colors:

- `.root` - Base background and font settings
- `.nav-bar` - Navigation bar styling
- `.primary-button` - Primary action buttons
- `.info-card` - Dashboard cards
- `.progress-bar` - Progress indicators
- `.table-view` - Metrics table styling

To customize a theme, edit the respective CSS file and modify the color values.

## Features Supporting Themes

All UI components adapt to the selected theme:

- ✅ Navigation bar and buttons
- ✅ Dashboard cards and metrics
- ✅ Compression/decompression views
- ✅ Progress bars and status indicators
- ✅ Settings panel
- ✅ Benchmark results
- ✅ File selection dialogs
- ✅ Tables and charts
- ✅ Input fields and controls
- ✅ Tooltips and alerts

## Theme Persistence

Theme settings are saved in the application's in-memory settings store and persist across:
- View switches (Dashboard ↔ Compress ↔ Settings)
- Application sessions (when saved)

**Note**: To persist theme settings across application restarts, ensure you click "Save Settings" before closing the application.
