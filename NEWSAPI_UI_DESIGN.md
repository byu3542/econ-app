# NewsAPI Integration & Smart Prompt UI Design

## Overview

This document shows how to integrate NewsAPI.org full article content with smart prompt suggestions in your AI Economic Analyst panel.

---

## Part A: NewsAPI.org Integration Setup

### Step 1: Get Free API Key

1. Go to https://newsapi.org
2. Sign up (free account)
3. Copy your API key
4. Add to `ApiConfig.java`:

```java
public class ApiConfig {
    // ... existing APIs ...
    
    // NewsAPI.org - Free tier: 100 requests/day
    public static final String NEWS_API_KEY = "your_api_key_here";
    public static final String NEWS_API_BASE_URL = "https://newsapi.org/v2";
}
```

### Step 2: Add to build.gradle

```gradle
dependencies {
    // ... existing deps ...
    
    // JSON parsing for NewsAPI
    implementation 'org.json:json:20231013'
}
```

### Step 3: Add Room DAO for Articles

Create `NewsArticleDao.java`:

```java
package com.economic.dashboard.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.economic.dashboard.models.NewsArticle;
import java.util.List;

@Dao
public interface NewsArticleDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<NewsArticle> articles);
    
    @Query("SELECT * FROM news_articles ORDER BY publishedAt DESC LIMIT :limit")
    LiveData<List<NewsArticle>> getRecentArticles(int limit);
    
    @Query("SELECT * FROM news_articles WHERE economicRelevanceScore >= :minScore ORDER BY publishedAt DESC")
    LiveData<List<NewsArticle>> getHighRelevanceArticles(int minScore);
    
    @Query("SELECT * FROM news_articles WHERE id = :articleId")
    LiveData<NewsArticle> getArticleById(int articleId);
    
    @Query("UPDATE news_articles SET isRead = 1 WHERE id = :articleId")
    void markAsRead(int articleId);
    
    @Query("UPDATE news_articles SET isFavorited = 1 WHERE id = :articleId")
    void markAsFavorited(int articleId);
    
    @Query("DELETE FROM news_articles WHERE cachedAtMillis < :timestamp")
    void deleteOlderThan(long timestamp);
}
```

### Step 4: Create NewsArticleRepository

```java
package com.economic.dashboard.api;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;

import com.economic.dashboard.database.NewsArticleDao;
import com.economic.dashboard.database.YieldDatabase;
import com.economic.dashboard.models.NewsArticle;

import java.util.List;

public class NewsArticleRepository {
    private static final String TAG = "NewsArticleRepository";
    private final NewsArticleDao dao;
    private final NewsApiClient newsApiClient;
    
    public NewsArticleRepository(Context context, String newsApiKey) {
        YieldDatabase database = YieldDatabase.getInstance(context);
        this.dao = database.newsArticleDao();
        this.newsApiClient = new NewsApiClient(newsApiKey);
    }
    
    /**
     * Fetch articles for a specific topic.
     */
    public void fetchArticlesForTopic(String query, int limit) {
        newsApiClient.searchRecentEconomicNews(query, new NewsApiClient.NewsCallback() {
            @Override
            public void onSuccess(List<NewsArticle> articles) {
                // Add cache timestamp
                long now = System.currentTimeMillis();
                for (NewsArticle article : articles) {
                    article.cachedAtMillis = now;
                }
                
                // Save to local database
                new Thread(() -> {
                    dao.insertAll(articles);
                    Log.d(TAG, "Cached " + articles.size() + " articles");
                }).start();
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "Error fetching articles: " + error);
            }
        });
    }
    
    /**
     * Get recent articles from cache.
     */
    public LiveData<List<NewsArticle>> getRecentArticles(int limit) {
        return dao.getRecentArticles(limit);
    }
    
    /**
     * Get high-relevance economic articles.
     */
    public LiveData<List<NewsArticle>> getHighRelevanceArticles(int minScore) {
        return dao.getHighRelevanceArticles(minScore);
    }
}
```

---

## Part B: Smart Prompt UI Design

### Layout Structure

```
┌──────────────────────────────────────────┐
│  AI ECONOMIC ANALYST                  [X]│
├──────────────────────────────────────────┤
│                                          │
│  📖 "Federal Reserve signals rate cuts"  │
│     Reuters • 2 hours ago • 🔴 HIGH      │
│                                          │
│  Article preview:                        │
│  "The Federal Reserve indicated..."      │
│  [Read Full Article →]                   │
│                                          │
├──────────────────────────────────────────┤
│  💡 SUGGESTED QUESTIONS                  │
├──────────────────────────────────────────┤
│                                          │
│  ☐ 🏦 How might this Fed action          │
│     affect Treasury yields and           │
│     bond prices?                         │
│                                          │
│  ☐ 📊 What's the historical impact       │
│     of similar Fed decisions on the      │
│     economy?                             │
│                                          │
│  ☐ 💰 How could this change affect       │
│     mortgage rates and real estate       │
│     market?                              │
│                                          │
│  [+ More Questions ↓]                    │
│                                          │
├──────────────────────────────────────────┤
│  Ask about this article...               │
│  [Type custom question          ] [Send] │
└──────────────────────────────────────────┘
```

### Card Components

#### 1. **Article Header Card**

```xml
<!-- res/layout/card_smart_article.xml -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp"
    android:background="@drawable/bg_article_card">

    <!-- Article Title -->
    <TextView
        android:id="@+id/tvArticleTitle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Federal Reserve signals rate cuts"
        android:textSize="16sp"
        android:textStyle="bold"
        android:textColor="#1a1a1a"
        android:lineSpacingMultiplier="1.2" />

    <!-- Source + Time + Relevance Badge -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginTop="8dp">

        <TextView
            android:id="@+id/tvSource"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Reuters"
            android:textSize="12sp"
            android:textColor="#666" />

        <TextView
            android:id="@+id/tvPublishedTime"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="2 hours ago"
            android:textSize="12sp"
            android:textColor="#666" />

        <!-- Relevance Badge -->
        <FrameLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp">

            <TextView
                android:id="@+id/tvRelevanceEmoji"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="🔴"
                android:textSize="14sp" />

            <TextView
                android:id="@+id/tvRelevanceLabel"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="HIGH"
                android:textSize="10sp"
                android:textColor="#fff"
                android:paddingStart="6dp"
                android:paddingEnd="6dp"
                android:paddingTop="2dp"
                android:paddingBottom="2dp"
                android:background="@drawable/bg_relevance_badge_high" />
        </FrameLayout>
    </LinearLayout>

    <!-- Article Preview -->
    <TextView
        android:id="@+id/tvPreview"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:text="The Federal Reserve indicated today that interest rate cuts may be imminent..."
        android:textSize="13sp"
        android:textColor="#333"
        android:lineSpacingMultiplier="1.1"
        android:maxLines="3"
        android:ellipsize="end" />

    <!-- Read Full Article Button -->
    <Button
        android:id="@+id/btnReadFull"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="10dp"
        android:text="Read Full Article →"
        android:textSize="12sp"
        android:padding="8dp"
        android:backgroundTint="@color/primary_blue"
        android:textColor="@color/white" />

</LinearLayout>
```

#### 2. **Smart Prompts Section**

```xml
<!-- res/layout/section_smart_prompts.xml -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingStart="16dp"
    android:paddingEnd="16dp"
    android:paddingTop="12dp">

    <!-- Header -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginBottom="12dp">

        <TextView
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="💡 SUGGESTED QUESTIONS"
            android:textSize="12sp"
            android:textStyle="bold"
            android:textColor="#666"
            android:letterSpacing="0.05" />

        <!-- Relevance indicator -->
        <TextView
            android:id="@+id/tvCategory"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Federal Reserve"
            android:textSize="11sp"
            android:textColor="#1a73e8"
            android:background="@drawable/bg_category_tag"
            android:paddingStart="8dp"
            android:paddingEnd="8dp"
            android:paddingTop="4dp"
            android:paddingBottom="4dp" />
    </LinearLayout>

    <!-- Prompts RecyclerView -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvPrompts"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:nestedScrollingEnabled="false" />

</LinearLayout>
```

#### 3. **Prompt Item Card**

```xml
<!-- res/layout/item_smart_prompt.xml -->
<com.google.android.material.card.MaterialCardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="8dp"
    android:clickable="true"
    android:focusable="true"
    android:checkable="true"
    android:foreground="?attr/selectableItemBackground"
    app:cardBackgroundColor="@color/card_background"
    app:cardElevation="0dp"
    app:strokeColor="@color/divider"
    app:strokeWidth="1dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp">

        <!-- Checkbox + Emoji + Prompt Text -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">

            <CheckBox
                android:id="@+id/cbSelect"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:button="@drawable/checkbox_custom" />

            <TextView
                android:id="@+id/tvPromptEmoji"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="🏦"
                android:textSize="18sp"
                android:layout_marginStart="8dp"
                android:layout_marginEnd="8dp" />

            <TextView
                android:id="@+id/tvPromptText"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="How might this Fed action affect Treasury yields and bond prices?"
                android:textSize="13sp"
                android:textColor="#1a1a1a"
                android:lineSpacingMultiplier="1.15" />
        </LinearLayout>

        <!-- Use This Prompt Button -->
        <Button
            android:id="@+id/btnUsePrompt"
            android:layout_width="match_parent"
            android:layout_height="36dp"
            android:layout_marginTop="8dp"
            android:text="Use This Prompt"
            android:textSize="12sp"
            android:backgroundTint="@color/primary_blue"
            android:textColor="@color/white" />
    </LinearLayout>

</com.google.android.material.card.MaterialCardView>
```

---

## Part C: Integration into AiAnalystBottomSheet

### Update Dialog Layout

Current dialog shows:
```
Recent News | Recent Economic Updates | Yields
[Chat interface]
```

**Enhanced version:**

```
Recent News | Recent Economic Updates | Yields
[Selected Article Card]
[Smart Prompts Section]
[Chat Interface with Quick Buttons]
```

### Code Implementation

```java
public class AiAnalystBottomSheet extends BottomSheetDialogFragment {
    
    private RecyclerView rvPrompts;
    private EditText etCustomPrompt;
    private Button btnSend;
    private NewsArticleRepository articleRepository;
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_ai_chat_enhanced, container, false);
        
        rvPrompts = view.findViewById(R.id.rvPrompts);
        etCustomPrompt = view.findViewById(R.id.etCustomPrompt);
        btnSend = view.findViewById(R.id.btnSend);
        
        // Initialize repository
        articleRepository = new NewsArticleRepository(
            requireContext(),
            ApiConfig.NEWS_API_KEY
        );
        
        return view;
    }
    
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Observe selected article
        observeSelectedArticle();
        
        // Handle prompt clicks
        setupPromptClickListeners();
        
        // Handle send button
        btnSend.setOnClickListener(v -> sendPrompt());
    }
    
    private void observeSelectedArticle() {
        // Get the currently selected article (passed via arguments or from shared ViewModel)
        NewsArticle article = getSelectedArticle();
        
        if (article != null) {
            // Generate smart prompts
            List<String> prompts = SmartPromptGenerator.generatePromptsForArticle(article);
            
            // Display in RecyclerView
            PromptAdapter adapter = new PromptAdapter(prompts, this::onPromptSelected);
            rvPrompts.setAdapter(adapter);
        }
    }
    
    private void onPromptSelected(String prompt) {
        // Pre-fill the text input
        etCustomPrompt.setText(prompt);
        // Focus for easy editing
        etCustomPrompt.requestFocus();
    }
    
    private void sendPrompt() {
        String prompt = etCustomPrompt.getText().toString().trim();
        if (!prompt.isEmpty()) {
            // Send to Claude API (existing implementation)
            sendToAnalyst(prompt);
            etCustomPrompt.setText("");
        }
    }
}
```

### PromptAdapter Implementation

```java
public class PromptAdapter extends RecyclerView.Adapter<PromptAdapter.ViewHolder> {
    
    private List<String> prompts;
    private OnPromptClickListener listener;
    
    public interface OnPromptClickListener {
        void onPromptClick(String prompt);
    }
    
    public PromptAdapter(List<String> prompts, OnPromptClickListener listener) {
        this.prompts = prompts;
        this.listener = listener;
    }
    
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_smart_prompt, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        String prompt = prompts.get(position);
        
        // Extract emoji (first character if emoji)
        String emoji = prompt.substring(0, prompt.indexOf(" "));
        String text = prompt.substring(prompt.indexOf(" ") + 1);
        
        holder.tvEmoji.setText(emoji);
        holder.tvText.setText(text);
        
        holder.btnUse.setOnClickListener(v -> {
            listener.onPromptClick(prompt);
            // Show Toast feedback
            Toast.makeText(v.getContext(), "Added to prompt", Toast.LENGTH_SHORT).show();
        });
        
        // Click entire card to select prompt
        holder.itemView.setOnClickListener(v -> listener.onPromptClick(prompt));
    }
    
    @Override
    public int getItemCount() {
        return prompts.size();
    }
    
    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvEmoji, tvText;
        Button btnUse;
        
        public ViewHolder(View itemView) {
            super(itemView);
            tvEmoji = itemView.findViewById(R.id.tvPromptEmoji);
            tvText = itemView.findViewById(R.id.tvPromptText);
            btnUse = itemView.findViewById(R.id.btnUsePrompt);
        }
    }
}
```

---

## Part D: Feature Breakdown

### Smart Prompt Categories

The prompts are auto-generated based on article topic:

| Topic | Emoji | Sample Prompts |
|-------|-------|----------------|
| **Fed/Interest Rates** | 🏦 | Treasury impact, historical precedent, mortgage effects |
| **Inflation** | 📈 | Acceleration trend, Fed response, sector impacts |
| **Employment** | 👥 | Economic health signal, historical comparison, Fed implications |
| **GDP Growth** | 📊 | Acceleration/deceleration, recession risk, trend comparison |
| **Housing** | 🏠 | Affordability, mortgage correlation, regional impact |
| **Debt/Deficit** | 💳 | Interest rate effects, sustainability, international comparison |
| **Wages** | 💰 | Inflation keeping pace, industry comparison, profitability impact |
| **Markets** | 📈 | Volatility drivers, earnings expectations, correlation analysis |

### Prompt Generation Logic

```
Article: "Federal Reserve signals rate cuts"
    ↓
SmartPromptGenerator.generatePromptsForArticle(article)
    ↓
Detects keywords: "fed", "rate", "cuts"
    ↓
Returns 5 contextual prompts:
  1. 🏦 How might this Fed action affect Treasury yields?
  2. 📊 What's the historical impact of similar Fed decisions?
  3. 💰 How could this change affect mortgage rates?
  4. 📉 What do markets expect to happen next?
  5. 🔮 When might we see the impact materialize?
```

---

## Part E: User Flow

### 1. **User Opens AI Analyst → Recent News Tab**

```
Flow:
  Recent News Tab
    ↓
  NewsApiClient fetches latest economic articles
    ↓
  NewsArticleRepository caches in Room DB
    ↓
  RecyclerView displays articles with:
    - Title + source + time
    - Economic relevance score (🔴 HIGH, 🟠 MEDIUM, 🟡 LOW)
    - Article preview
    - "Read Full Article" link
```

### 2. **User Taps Article**

```
Flow:
  User clicks article card
    ↓
  Article details expand/open detail view
    ↓
  SmartPromptGenerator creates 5 contextual questions
    ↓
  Prompts displayed with checkboxes + "Use This Prompt" buttons
    ↓
  User can:
    a) Click "Use This Prompt" → pre-fills chat input
    b) Edit the prompt manually
    c) Type custom question
    d) Click checkbox to select multiple prompts for batch analysis
```

### 3. **User Sends Prompt**

```
Flow:
  "🏦 How might this Fed action affect Treasury yields?"
    ↓
  Sent to Claude (existing implementation)
    ↓
  Response includes:
    - Direct answer
    - Cross-reference to live Treasury data
    - Historical context
    - Prediction for next steps
```

---

## Part F: Advanced Features (Future)

### 1. **Batch Article Analysis**
- Select multiple articles with checkboxes
- Generate: "Compare these 3 Fed-related stories..."
- Get unified economic narrative

### 2. **Timeline Analysis**
- Show how sentiment evolved (March → April → May)
- "Economic narrative shifted from X to Y in 6 weeks"

### 3. **Asset Impact Scoring**
- Red pill: Article likely to affect stocks, bonds, crypto
- Show: "This news is 🔴 for bonds, 🟢 for stocks"

### 4. **Prediction Chains**
- "If Fed cuts rates → mortgage rates fall → housing demand rises"
- Interactive cause-and-effect visualization

---

## Part G: Performance Considerations

### Caching Strategy

```java
// Cache articles for 24 hours
new Thread(() -> {
    long twentyFourHoursAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
    newsArticleDao.deleteOlderThan(twentyFourHoursAgo);
}).start();
```

### NewsAPI Quota Management

```
Free tier: 100 requests/day
Recommended usage:
  - Morning refresh (6 AM): 20 requests
  - Afternoon refresh (12 PM): 20 requests  
  - Evening refresh (6 PM): 20 requests
  - On-demand user queries: 40 requests
  = 100 requests/day optimal
```

### Search Strategy

```java
// Instead of searching for everything:
newsApiClient.searchRecentEconomicNews("inflation"); // 1 request

// Don't do this (wastes quota):
for (String topic : allTopics) {
    newsApiClient.searchNews(topic); // 10+ requests
}
```

---

## Summary

| Feature | Effort | Impact |
|---------|--------|--------|
| NewsAPI integration | Low | High (full articles vs headlines) |
| Smart prompts | Low | Very High (UX improvement) |
| Batch analysis | Medium | High (sophisticated analysis) |
| Timeline visualization | Medium | Medium (contextual understanding) |
| Prediction chains | High | High (educational value) |

**Recommended Phase 1:** NewsAPI + Smart Prompts (2-3 weeks implementation)
**Expected user impact:** 3-5x increase in analyst feature usage

