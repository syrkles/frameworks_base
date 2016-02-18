/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.documentsui;

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.Shared.TAG;
import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.util.Range;

import com.android.documentsui.dirlist.Model;
import com.android.documentsui.model.DocumentInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides support for gather a list of quick-viewable files into a quick view intent.
 */
final class QuickViewIntentBuilder {
    private static final int MAX_CLIP_ITEMS = 1000;

    private final DocumentInfo mDocument;
    private final Model mModel;

    private final PackageManager mPkgManager;
    private final Resources mResources;

    public QuickViewIntentBuilder(
            PackageManager pkgManager,
            Resources resources,
            DocumentInfo doc,
            Model model) {

        mPkgManager = pkgManager;
        mResources = resources;
        mDocument = doc;
        mModel = model;
    }

    /**
     * Builds the intent for quick viewing. Short circuits building if a handler cannot
     * be resolved; in this case {@code null} is returned.
     */
    @Nullable Intent build() {
        if (DEBUG) Log.d(TAG, "Preparing intent for doc:" + mDocument.documentId);

        String trustedPkg = mResources.getString(R.string.trusted_quick_viewer_package);

        if (!TextUtils.isEmpty(trustedPkg)) {
            Intent intent = new Intent(Intent.ACTION_QUICK_VIEW);
            intent.setDataAndType(mDocument.derivedUri, mDocument.mimeType);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setPackage(trustedPkg);
            if (hasRegisteredHandler(intent)) {
                final ArrayList<Uri> uris = new ArrayList<Uri>();
                final int documentLocation = collectViewableUris(uris);
                final Range<Integer> range = computeSiblingsRange(uris, documentLocation);

                ClipData clipData = null;
                ClipData.Item item;
                Uri uri;
                for (int i = range.getLower(); i <= range.getUpper(); i++) {
                    uri = uris.get(i);
                    item = new ClipData.Item(uri);
                    if (DEBUG) Log.d(TAG, "Including file: " + uri);
                    if (clipData == null) {
                        clipData = new ClipData(
                                "URIs", new String[] { ClipDescription.MIMETYPE_TEXT_URILIST },
                                item);
                    } else {
                        clipData.addItem(item);
                    }
                }

                intent.putExtra(Intent.EXTRA_INDEX, documentLocation);
                intent.setClipData(clipData);

                return intent;
            } else {
                Log.e(TAG, "Can't resolve trusted quick view package: " + trustedPkg);
            }
        }

        return null;
    }

    private int collectViewableUris(ArrayList<Uri> uris) {
        final List<String> siblingIds = mModel.getModelIds();
        uris.ensureCapacity(siblingIds.size());

        int documentLocation = 0;
        Cursor cursor;
        String mimeType;
        String id;
        String authority;
        Uri uri;

        // Cursor's are not guaranteed to be immutable. Hence, traverse it only once.
        for (int i = 0; i < siblingIds.size(); i++) {
            cursor = mModel.getItem(siblingIds.get(i));

            mimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                continue;
            }

            id = getCursorString(cursor, Document.COLUMN_DOCUMENT_ID);
            authority = getCursorString(cursor, RootCursorWrapper.COLUMN_AUTHORITY);
            uri = DocumentsContract.buildDocumentUri(authority, id);

            if (id.equals(mDocument.documentId)) {
                if (DEBUG) Log.d(TAG, "Found starting point for QV. " + i);
                documentLocation = i;
            }

            uris.add(uri);
        }

        return documentLocation;
    }

    private static Range<Integer> computeSiblingsRange(List<Uri> uris, int documentLocation) {
        // Restrict number of siblings to avoid hitting the IPC limit.
        // TODO: Remove this restriction once ClipData can hold an arbitrary number of
        // items.
        int firstSibling;
        int lastSibling;
        if (documentLocation < uris.size() / 2) {
            firstSibling = Math.max(0, documentLocation - MAX_CLIP_ITEMS / 2);
            lastSibling = Math.min(uris.size() - 1, firstSibling + MAX_CLIP_ITEMS - 1);
        } else {
            lastSibling = Math.min(uris.size() - 1, documentLocation + MAX_CLIP_ITEMS / 2);
            firstSibling = Math.max(0, lastSibling - MAX_CLIP_ITEMS + 1);
        }

        if (DEBUG) Log.d(TAG, "Copmuted siblings from index: " + firstSibling
                + " to: " + lastSibling);

        return new Range(firstSibling, lastSibling);
    }

    private boolean hasRegisteredHandler(Intent intent) {
        // Try to resolve the intent. If a matching app isn't installed, it won't resolve.
        return intent.resolveActivity(mPkgManager) != null;
    }
}
