package com.mapzen.privatemaps

import com.mapzen.pelias.gson.Result

public trait MainPresenter {
    public var currentSearchTerm: String?
    public var viewController: ViewController?

    public fun onSearchResultsAvailable(result: Result?)
    public fun onSearchResultSelected(position: Int)
    public fun restoreViewState()
    public fun onCollapseSearchView()
    public fun onQuerySubmit()
}
