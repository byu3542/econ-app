package com.economic.dashboard.news;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NewsViewModel extends AndroidViewModel {

    private final MutableLiveData<List<NewsItem>> newsItems    = new MutableLiveData<>();
    private final MutableLiveData<Boolean>        isLoading    = new MutableLiveData<>(false);
    private final MutableLiveData<String>         errorMessage = new MutableLiveData<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final NewsRepository  repository = NewsRepository.getInstance();

    private List<NewsItem> fullList   = new ArrayList<>();
    private String         currentFilter = null; // null = show all

    public NewsViewModel(@NonNull Application application) {
        super(application);
    }

    public LiveData<List<NewsItem>> getNewsItems()    { return newsItems; }
    public LiveData<Boolean>        getIsLoading()    { return isLoading; }
    public LiveData<String>         getErrorMessage() { return errorMessage; }

    public void loadNews(boolean forceRefresh) {
        isLoading.setValue(true);
        errorMessage.setValue(null);

        executor.execute(() -> {
            try {
                List<NewsItem> result = repository.fetchAllFeeds(forceRefresh);
                fullList = result != null ? result : new ArrayList<>();
                postFilteredList();
            } catch (Exception e) {
                errorMessage.postValue("Failed to load news. Check your connection.");
            } finally {
                isLoading.postValue(false);
            }
        });
    }

    public void setFilter(String tag) {
        currentFilter = tag;
        postFilteredList();
    }

    private void postFilteredList() {
        if (currentFilter == null) {
            newsItems.postValue(new ArrayList<>(fullList));
            return;
        }
        List<NewsItem> filtered = new ArrayList<>();
        for (NewsItem item : fullList) {
            if (currentFilter.equals(item.tag)) {
                filtered.add(item);
            }
        }
        newsItems.postValue(filtered);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdown();
    }
}
