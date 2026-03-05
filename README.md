# 📊 Economic Dashboard — Android App

Android port of your "Economic API's" Google Sheet. Connects to the same APIs with the same keys and renders equivalent charts and dashboard data.

---

## 🗂 App Structure (mirrors your Sheet tabs)

| Tab | What it shows | Source |
|-----|--------------|--------|
| **Dashboard** | Latest values for all metrics | All APIs |
| **Treasury** | Yield Curve snapshot + 10-Year trend | Treasury.gov XML |
| **GDP** | Quarterly GDP growth rate chart | BEA API |
| **Employment** | Unemployment Rate + Labor Participation | BLS API |
| **CPI** | CPI-U and CPI-W index charts | BLS API |
| **Wages** | Avg Hourly/Weekly earnings + Inflation vs Wages comparison | BLS API |

---

## 🔑 API Keys (already configured)

Your keys from the Apps Script are pre-loaded in `ApiConfig.java`:

```java
BEA:  13425E5B-2ECF-4378-9786-E48A81B1AEAE
BLS:  5198938c150e4e93a417f3769a5cc077
```

**Treasury.gov** — No key required (public XML feed).

---

## 🚀 Setup Instructions

### Prerequisites
- Android Studio (Hedgehog 2023.1.1 or newer)
- Android SDK 26+
- Java 8+

### Steps

1. **Open in Android Studio**
   - File → Open → select the `EconomicDashboard` folder

2. **Sync Gradle**
   - Android Studio will prompt to sync dependencies
   - Click "Sync Now" — this downloads MPAndroidChart, Retrofit, OkHttp, etc.

3. **Build & Run**
   - Connect an Android device (API 26+) or start an emulator
   - Click the green ▶ Run button

4. **On first launch**
   - The app automatically fetches all data on startup
   - Pull-to-refresh on the Dashboard tab to manually refresh
   - Tap the 🔄 refresh icon in the top toolbar for full refresh

---

## 📦 Dependencies

```gradle
// Charts
com.github.PhilJay:MPAndroidChart:v3.1.0

// Networking
com.squareup.retrofit2:retrofit:2.9.0
com.squareup.retrofit2:converter-gson:2.9.0
com.squareup.okhttp3:okhttp:4.12.0

// UI
com.google.android.material:material:1.11.0
androidx.swiperefreshlayout:swiperefreshlayout:1.1.0
```

---

## 📐 Architecture

```
EconomicRepository       — Fetches from Treasury, BEA, BLS APIs
       ↓
EconomicViewModel        — LiveData, manages fetch state
       ↓
MainActivity             — ViewPager2 + TabLayout
       ↓
6 Fragments              — Dashboard, Treasury, GDP, Employment, CPI, Wages
```

---

## 🗂 File Map

```
app/src/main/java/com/economic/dashboard/
├── api/
│   ├── ApiConfig.java          ← API keys + series IDs
│   ├── EconomicApiService.java ← Retrofit interface
│   ├── EconomicRepository.java ← All fetch logic (Treasury XML, BEA, BLS)
│   └── RetrofitClient.java     ← HTTP clients
├── models/
│   ├── EconomicDataPoint.java  ← Universal data model
│   ├── BlsResponse.java        ← BLS JSON model
│   └── BeaResponse.java        ← BEA JSON model
├── ui/
│   ├── MainActivity.java
│   ├── EconomicViewModel.java  ← LiveData + fetch orchestration
│   ├── EconomicPagerAdapter.java
│   └── fragments/
│       ├── DashboardFragment.java    ← Latest values table
│       ├── TreasuryFragment.java     ← Yield curve + 10Y trend
│       ├── GdpFragment.java          ← GDP line chart
│       ├── EmploymentFragment.java   ← Unemployment + Labor charts
│       ├── CpiFragment.java          ← CPI-U and CPI-W charts
│       └── WagesFragment.java        ← Wages + CPI vs Wages comparison
└── utils/
    └── ChartHelper.java         ← Consistent chart styling
```

---

## 📊 Charts Implemented

| Chart | Type | Matches Sheet |
|-------|------|--------------|
| Treasury Yield Curve (latest) | Line (cubic bezier) | ✅ buildYieldCurveChart() |
| 10-Year Yield Trend | Line | ✅ build10YearChart() |
| Unemployment Rate | Line | ✅ buildUnemploymentChart() |
| Labor Force Participation | Line | ✅ |
| CPI-U All Items | Line | ✅ buildCPIChart() |
| CPI-W All Items | Line | ✅ |
| Avg Hourly Earnings | Line | ✅ buildWageChart() |
| Inflation vs Wages (indexed) | Dual-line | ✅ buildComparisonChart() |

---

## 🔄 Data Sources

| Source | Endpoint | Data |
|--------|----------|------|
| Treasury.gov | XML feed | Daily yield curve |
| BEA API | apps.bea.gov/api/data | GDP (NIPA T10101) |
| BLS API v2 | api.bls.gov/publicAPI/v2 | CPI, Employment, Wages |

---

## 🛠 Troubleshooting

**Charts are empty:**
- Check internet connection
- Pull to refresh on Dashboard
- BEA/BLS APIs can be slow — wait 10-15 seconds

**Build fails:**
- Make sure JitPack is in `settings.gradle` repositories (already included)
- Gradle sync: File → Sync Project with Gradle Files

**Treasury data missing:**
- Treasury XML feed only provides data for the current year
- Prior year data won't appear until Jan 1

---

## 📝 Notes

- Data is fetched live each app session (no local caching), matching the Sheet's "append only" behavior
- The comparison chart (Inflation vs Wages) indexes both series to 100 at the earliest data point, exactly as in `buildComparisonChart()` in your Apps Script
- Chart colors match your Apps Script: Treasury=`#1a73e8`, GDP=`#1a73e8`, Unemployment=`#ea4335`, CPI=`#fbbc04`, Wages=`#9c27b0`
