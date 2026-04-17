# NewsAPI Integration Checklist

Use this to track implementation of NewsAPI.org + Smart Prompts feature.

---

## Phase 1: Setup (30 minutes)

### Get API Key & Configure
- [ ] Sign up at https://newsapi.org (free account)
- [ ] Copy API key from dashboard
- [ ] Add to `ApiConfig.java`:
  ```java
  public static final String NEWS_API_KEY = "your_api_key";
  ```

### Add Dependencies
- [ ] Add to `app/build.gradle`:
  ```gradle
  implementation 'org.json:json:20231013'
  ```
- [ ] Sync Gradle
- [ ] Rebuild project

### Create Java Files
- [ ] Copy `NewsApiClient.java` to `api/` package
- [ ] Copy `NewsArticle.java` to `models/` package
- [ ] Copy `SmartPromptGenerator.java` to `analyst/` package
- [ ] Verify all package declarations are correct

---

## Phase 2: Database Layer (20 minutes)

### Create NewsArticleDao
- [ ] Create `NewsArticleDao.java` in `database/` package
- [ ] Add these methods:
  - `insertAll(List<NewsArticle>)`
  - `getRecentArticles(int limit)`
  - `getHighRelevanceArticles(int minScore)`
  - `getArticleById(int id)`
  - `markAsRead(int id)`
  - `markAsFavorited(int id)`
  - `deleteOlderThan(long timestamp)`

### Update YieldDatabase
- [ ] Add `newsArticleDao()` abstract method to `YieldDatabase.java`
- [ ] Update `@Database` annotation:
  ```java
  @Database(entities = {TreasuryYield.class, NewsArticle.class}, version = 2)
  ```
- [ ] Increment version number (was 1, now 2)

### Create NewsArticleRepository
- [ ] Create `NewsArticleRepository.java` in `api/` package
- [ ] Implement:
  - Constructor taking Context + API key
  - `fetchArticlesForTopic(String query)`
  - `getRecentArticles(int limit)`
  - `getHighRelevanceArticles(int minScore)`

---

## Phase 3: UI Components (1 hour)

### Create Layout Files
- [ ] `res/layout/card_smart_article.xml` (article header)
- [ ] `res/layout/section_smart_prompts.xml` (prompts container)
- [ ] `res/layout/item_smart_prompt.xml` (individual prompt card)
- [ ] `res/drawable/bg_article_card.xml` (background shape)
- [ ] `res/drawable/bg_relevance_badge_high.xml` (relevance badge)
- [ ] `res/drawable/bg_category_tag.xml` (category label)

### Create Adapters
- [ ] Create `PromptAdapter.java` in `ui/` package
- [ ] Implement:
  - `onCreateViewHolder()`
  - `onBindViewHolder()`
  - Click listeners for prompts
  - `OnPromptClickListener` interface

### Update AiAnalystBottomSheet
- [ ] Add RecyclerView for prompts
- [ ] Integrate `SmartPromptGenerator`
- [ ] Connect prompt clicks to chat input
- [ ] Add "Read Full Article" button

---

## Phase 4: Integration (1 hour)

### Connect to News Tab
- [ ] Update `NewsFragment.java` to use `NewsArticleRepository`
- [ ] Fetch articles on tab load
- [ ] Display in list with relevance badges
- [ ] Handle article selection

### Add to ViewModel
- [ ] Create `NewsViewModel.java` (if not exists)
- [ ] Add `articleRepository` field
- [ ] Add `getArticles()` LiveData
- [ ] Add `selectArticle(article)` method

### Wire Up Chat Integration
- [ ] When article selected → generate prompts
- [ ] Display prompts in bottom sheet
- [ ] Pre-fill chat when user clicks prompt
- [ ] Send to Claude with article context

---

## Phase 5: Testing (30 minutes)

### Test API Connection
- [ ] [ ] Verify API key works (test with curl first)
  ```bash
  curl "https://newsapi.org/v2/everything?q=Federal%20Reserve&apiKey=YOUR_KEY"
  ```
- [ ] Check response returns articles
- [ ] Verify relevance scores calculated correctly

### Test Database
- [ ] Articles save to Room database
- [ ] Articles retrieved from cache
- [ ] Old articles deleted after 24 hours
- [ ] Relevance filtering works (getHighRelevanceArticles)

### Test UI
- [ ] Articles display in Recent News tab
- [ ] Relevance badges show correct emoji (🔴 🟠 🟡 🟢 ⚪)
- [ ] Click article → opens detail view
- [ ] Smart prompts generate for each article type

### Test Prompts
- [ ] Fed headlines generate Fed-related prompts
- [ ] Inflation headlines generate inflation prompts
- [ ] Click "Use This Prompt" → pre-fills chat
- [ ] Can edit prompt before sending
- [ ] Custom prompts still work

### Test Claude Integration
- [ ] Send prompt to Claude API
- [ ] Response includes article context
- [ ] Historical data referenced (Treasury, Fed rates)
- [ ] User can follow-up questions

---

## Phase 6: Polish (30 minutes)

### Performance
- [ ] Fetch articles in background (don't block UI)
- [ ] Cache articles locally (avoid re-fetching)
- [ ] Lazy load article images
- [ ] Limit API calls to 100/day (quota management)

### Error Handling
- [ ] Show error if API fails
- [ ] Fall back to cached articles
- [ ] Display "no articles found" message
- [ ] Handle network errors gracefully

### UX Polish
- [ ] Add loading indicator while fetching
- [ ] Smooth transitions between articles
- [ ] "Read Full Article" link opens in browser
- [ ] Keyboard close after sending prompt
- [ ] Visual feedback on button clicks

### Documentation
- [ ] Add comments to prompt generation logic
- [ ] Document API rate limits (100 requests/day)
- [ ] Note: NewsAPI articles are syndicated (must attribute source)

---

## Common Issues & Solutions

| Issue | Solution |
|-------|----------|
| "Invalid API key" | Verify key in ApiConfig matches newsapi.org dashboard |
| "No articles found" | Check API endpoint URL, query parameters |
| Prompts not generating | Ensure SmartPromptGenerator keyword matching works |
| Articles not caching | Verify Room DAO methods, check database migration |
| Slow app | Fetch articles in background thread, don't block UI |
| Missing images | Set fallback placeholder image if imageUrl is empty |

---

## Quota Management

NewsAPI free tier: **100 requests/day**

Recommended allocation:
```
Morning refresh (6 AM):    20 requests (4 topics × 5 articles)
Afternoon refresh (12 PM): 20 requests
Evening refresh (6 PM):    20 requests
On-demand searches:        40 requests (user-initiated)
────────────────────────────────────────
Total:                     100 requests/day
```

**Optimization tips:**
- Cache articles for 24 hours (avoid re-fetching)
- Only search when user opens "Recent News" tab
- Batch multiple topics in single query if possible
- Show cached articles while fetching fresh ones

---

## Success Criteria

✅ **Phase complete when:**
- [ ] Articles fetch from NewsAPI successfully
- [ ] Full article content displays (not just headlines)
- [ ] Smart prompts generate based on article topic
- [ ] User can click prompt → pre-fills chat
- [ ] Claude receives article context in prompt
- [ ] No crashes or lint errors
- [ ] 100 API calls/day quota sufficient for typical usage

---

## Timeline Estimate

| Phase | Time | Status |
|-------|------|--------|
| 1. Setup | 30 min | ⏳ |
| 2. Database | 20 min | ⏳ |
| 3. UI | 60 min | ⏳ |
| 4. Integration | 60 min | ⏳ |
| 5. Testing | 30 min | ⏳ |
| 6. Polish | 30 min | ⏳ |
| **Total** | **3-4 hours** | **⏳** |

---

## Next Steps After Completion

1. **Monitor API Usage**
   - Track daily API calls via newsapi.org dashboard
   - Adjust refresh frequency if approaching 100 requests/day limit

2. **User Feedback**
   - Ask if Smart Prompts are helpful
   - Adjust prompt generation based on feedback
   - Add more prompt types if needed

3. **Advanced Features**
   - Batch article analysis (select 3+ articles)
   - Timeline sentiment analysis
   - Prediction chains ("If X, then Y")
   - Asset impact scoring

4. **Upgrade Plan**
   - Current: Free tier (100 req/day)
   - Medium: $45/month (500 req/day)
   - High: Custom (5,000+ req/day)

---

## Remember

- NewsAPI articles are **syndicated content** → must link to original source
- Free tier has **24-hour rate limit**, not per-minute
- Articles cached for **24 hours** to avoid wasted requests
- Smart Prompts should feel **natural and contextual**, not generic

