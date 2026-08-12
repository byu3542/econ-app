package com.economic.dashboard.news

import android.app.Application

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

import java.util.concurrent.Executors

class NewsViewModel(application: Application) : AndroidViewModel(application) {

    private val newsItems = MutableLiveData<List<NewsItem>>()
    private val isLoading = MutableLiveData(false)
    // Nullable payload: loadNews() clears the previous error with setValue(null).
    private val errorMessage = MutableLiveData<String?>()
    // P6 TICKET-36: epoch millis of the last successful feed load — the real
    // freshness behind the header's "Updated …" label (was a hardcoded string).
    private val lastFetchMillis = MutableLiveData<Long?>()

    private val executor = Executors.newSingleThreadExecutor()
    private val repository = NewsRepository.getInstance()

    private var fullList: List<NewsItem> = ArrayList()
    private var currentFilter: String? = null // null = show all

    fun getNewsItems(): LiveData<List<NewsItem>> = newsItems
    fun getIsLoading(): LiveData<Boolean> = isLoading
    fun getErrorMessage(): LiveData<String?> = errorMessage
    fun getLastFetchMillis(): LiveData<Long?> = lastFetchMillis

    fun loadNews(forceRefresh: Boolean) {
        isLoading.setValue(true)
        errorMessage.setValue(null)

        executor.execute {
            try {
                // fetchAllFeeds returns a non-null List (Kotlin, batch 8), so the
                // original's null fallback is statically dead.
                fullList = repository.fetchAllFeeds(forceRefresh)
                lastFetchMillis.postValue(System.currentTimeMillis())
                postFilteredList()
            } catch (e: Exception) {
                errorMessage.postValue("Failed to load news. Check your connection.")
            } finally {
                isLoading.postValue(false)
            }
        }
    }

    fun setFilter(tag: String?) {
        currentFilter = tag
        postFilteredList()
    }

    private fun postFilteredList() {
        val filter = currentFilter
        if (filter == null) {
            newsItems.postValue(ArrayList(fullList))
            return
        }
        val filtered = ArrayList<NewsItem>()
        for (item in fullList) {
            if (filter == item.tag) {
                filtered.add(item)
            }
        }
        newsItems.postValue(filtered)
    }

    override fun onCleared() {
        super.onCleared()
        executor.shutdown()
    }
}
