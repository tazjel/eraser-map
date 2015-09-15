package com.mapzen.erasermap;

import com.mapzen.helpers.RouteEngine;
import com.mapzen.pelias.SavedSearch;

import com.squareup.otto.Bus;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class CommonModule {
    @Provides @Singleton SavedSearch provideSavedSearch() {
        return new SavedSearch();
    }

    @Provides @Singleton Bus provideBus() {
        return new Bus();
    }

    @Provides @Singleton RouteEngine provideRouteEngine() {
        return new RouteEngine();
    }
}
