/*
 * Copyright © 2016 IBM Corp. All rights reserved.
 *
 * Original iOS version by  Jens Alfke, ported to Android by Marty Schoch
 * Copyright © 2012 Couchbase, Inc. All rights reserved.
 *
 * Modifications for this distribution by Cloudant, Inc., Copyright © 2013 Cloudant, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.sync.documentstore;

import com.cloudant.sync.event.EventBus;
import com.cloudant.sync.query.Query;

import java.io.File;
import java.util.Iterator;
import java.util.List;

/**
 * <p>The Datastore is the core interaction point for create, delete and update
 * operations (CRUD) for within Cloudant Sync.</p>
 *
 * <p>The Datastore can be viewed as a pool of heterogeneous JSON documents. One
 * datastore can hold many different types of document, unlike tables within a
 * relational model. The datastore exposes a simple key-value model where the key
 * is the document ID combined with a (sometimes optional) revision ID.</p>
 *
 * <p>For a more advanced way of querying the Datastore, see the
 * {@link Query} class</p>
 *
 * <p>Each document consists of a set of revisions, hence most methods within
 * this class operating on {@link DocumentRevision} objects, which carry both a
 * document ID and a revision ID. This forms the basis of the MVCC data model,
 * used to ensure safe peer-to-peer replication is possible.</p>
 *
 * <p>Each document is formed of a tree of revisions. Replication can create
 * branches in this tree when changes have been made in two or more places to
 * the same document in-between replications. MVCC exposes these branches as
 * conflicted documents. These conflicts should be resolved by user-code, by
 * marking all but one of the leaf nodes of the branches as "deleted", using
 * the {@link Database#delete(DocumentRevision)}
 * method. When the
 * datastore is next replicated with a remote datastore, this fix will be
 * propagated, thereby resolving the conflicted document across the set of
 * peers.</p>
 *
 * @see DocumentRevision
 * @see Query
 * @api_public
 *
 */
public interface Database {

    /**
     * The sequence number of the datastore when no updates have been made,
     * {@value}.
     */
    long SEQUENCE_NUMBER_START = -1l;

    /**
     * <p>Returns the path where this Database is stored.</p>
     *
     * @return The path where this Database is stored.
     */
    File getPath();

    /**
     * <p>Returns the current winning revision of a document.</p>
     *
     * <p>Previously deleted documents can be retrieved
     * (via tombstones, see {@link Database#delete(DocumentRevision)})
     * </p>
     *
     * @param documentId ID of document to retrieve.
     * @return {@code DocumentRevision} of the document
     * @throws DocumentNotFoundException if the document specified was not found
     * @throws DocumentStoreException if there was an error accessing database
     */
    DocumentRevision get(String documentId) throws DocumentNotFoundException, DocumentStoreException;

    /**
     * <p>Retrieves a given revision of a document.</p>
     *
     * <p>This method gets the revision of a document with a given ID. As the
     * datastore prunes the content of old revisions to conserve space, this
     * revision may contain the metadata but not content of the revision.</p>
     *
     * <p>Previously deleted documents can be retrieved
     * (via tombstones, see {@link Database#delete(DocumentRevision)})
     * </p>
     *
     * @param documentId ID of the document
     * @param revisionId Revision of the document
     * @return {@code DocumentRevision} of the document
     * @throws DocumentNotFoundException if the document specified was not found
     * @throws DocumentStoreException if there was an error reading the document
     */
    DocumentRevision get(String documentId, String revisionId) throws
            DocumentNotFoundException, DocumentStoreException;

    /**
     * <p>Returns whether this datastore contains a particular revision of
     * a document.</p>
     *
     * <p>{@code true} will still be returned if the document is deleted.</p>
     *
     * @param documentId id of the document
     * @param revisionId revision of the document
     * @return {@code true} if specified document's particular revision exists
     *         in the datastore, {@code false} otherwise.
     * @throws DocumentStoreException if there was an error reading from the database
     */
    boolean contains(String documentId, String revisionId) throws DocumentStoreException;

    /**
     * <p>Returns whether this datastore contains any revisions of a document.
     * </p>
     *
     * <p>{@code true} will still be returned if the document is deleted.</p>
     *
     * @param documentId id of the document
     * @return {@code true} if specified document exists
     *         in the datastore, {@code false} otherwise.
     * @throws DocumentStoreException if there was an error reading from the database
     */
    boolean contains(String documentId) throws DocumentStoreException;

    /**
     * <p>Enumerates the current winning revision for all documents in the
     * datastore.</p>
     *
     * <p>Logically, this method takes all the documents in either ascending
     * or descending order, skips all documents up to {@code offset} then
     * returns up to {@code limit} document revisions, stopping either
     * at {@code limit} or when the list of document is exhausted.</p>
     *
     * @param offset start position
     * @param limit maximum number of documents to return
     * @param descending whether the documents are read in ascending or
     *                   descending order.
     * @return list of {@code DBObjects}, maximum length {@code limit}.
     * @throws DocumentStoreException if there was an error reading the documents
     */
    List<DocumentRevision> get(int offset, int limit, boolean descending) throws DocumentStoreException;

    /**
     * <p>Enumerates the current winning revision for all documents in the
     * datastore and return a list of their document identifiers.</p>
     *
     * @return list of {@code String}.
     * @throws DocumentStoreException if there was an error reading the document IDs
     */
    List<String> getIds() throws DocumentStoreException;

    /**
     * <p>Returns the current winning revisions for a set of documents.</p>
     *
     * <p>If the {@code documentIds} list contains document IDs not present
     * in the datastore, they will be skipped and there will be no entry for
     * them in the returned list.</p>
     *
     * @param documentIds list of document id
     * @return list of {@code DocumentRevision} objects.
     * @throws DocumentStoreException if there was an error retrieving the
     * documents.
     */
    List<DocumentRevision> get(List<String> documentIds) throws DocumentStoreException;

    /**
     * <p>Retrieves the datastore's current sequence number.</p>
     *
     * <p>The datastore's sequence number is incremented every time the
     * content of the datastore is changed. Each document revision within the
     * datastore has an associated sequence number, describing when the change
     * took place.</p>
     *
     * <p>The sequence number is particularly useful to find out the changes
     * to the database since a given time. For example, replication uses the
     * sequence number so only the changes since the last replication are
     * sent. Indexing could also use this in a similar manner.</p>
     *
     * @return the last sequence number
     */
    long getLastSequence() throws DocumentStoreException;

    /**
     * <p>Return the number of documents in the datastore</p>
     *
     * @return number of non-deleted documents in datastore
     */
    int getDocumentCount() throws DocumentStoreException;

    /**
     * <p>Returns a list of changed documents, from {@code since} to
     * {@code since + limit}, inclusive.</p>
     *
     * @param since the lower bound (exclusive) of the change set
     *              sequence number
     * @param limit {@code since + limit} is the upper bound (inclusive) of the
     *              change set sequence number
     * @return list of the documents and last sequence number of the change set
     *      (checkpoint)
     */
    Changes changes(long since, int limit) throws DocumentStoreException;

    /**
     * <p>Return {@code @Iterable<String>} over ids to all the Documents with
     * conflicted revisions.</p>
     *
     * <p>Document is modeled as a tree. If a document has at least two leaf
     * revisions that not deleted, those leaf revisions considered
     * conflicted revisions of the document.
     * </p>
     *
     * @return Iterable of String over ids of all Documents with
     *         conflicted revisions
     *
     * @see <a href="http://wiki.apache.org/couchdb/Replication_and_conflicts">Replication and conflicts</a>
     */
    Iterator<String> getConflictedIds() throws DocumentStoreException;

    /**
     * <p>
     * Resolve conflicts for specified Document using the
     * given {@code ConflictResolver}
     * </p>
     *
     * @param docId id of Document to resolve conflicts
     * @param resolver the ConflictResolver used to resolve
     *                 conflicts
     * @throws ConflictException Found new conflicts while
     *         resolving the existing conflicted revision.
     *         This is very likely caused by new conflicted
     *         revision are added while the resolver is
     *         running.
     *
     * @see ConflictResolver
     */
    void resolveConflicts(String docId, ConflictResolver resolver)
        throws ConflictException;

    /**
     * <p>Adds a new document with body and attachments from <code>rev</code>.</p>
     *
     * <p>If the ID in <code>rev</code> is null, the document's ID will be auto-generated,
     * and can be found by inspecting the returned {@code DocumentRevision}.</p>
     *
     * <p>If the document is successfully created, a
     * {@link com.cloudant.sync.event.notifications.DocumentCreated DocumentCreated}
     * event is posted on the event bus.</p>
     *
     * @param rev the <code>DocumentRevision</code> to be created
     * @return a <code>DocumentRevision</code> - the newly created document
     * @throws AttachmentException if there was an error saving any new attachments
     * @throws InvalidDocumentException if the document body was invalid
     * @throws ConflictException if a document with this document id already exists
     * @throws DocumentStoreException if there was an error creating the document
     * @see Database#getEventBus()
     */
    DocumentRevision create(DocumentRevision rev) throws AttachmentException,
            InvalidDocumentException, ConflictException, DocumentStoreException;

    /**
     * <p>Updates a document that exists in the datastore with with body and attachments
     * from <code>rev</code>.
     * </p>
     *
     * <p>{@code rev} must be a current revision for this document.</p>
     *
     * <p>If the document is successfully updated, a
     * {@link com.cloudant.sync.event.notifications.DocumentUpdated DocumentUpdated}
     * event is posted on the event bus.</p>
     *
     * @param rev the <code>DocumentRevision</code> to be updated
     * @return a <code>DocumentRevision</code> - the updated document
     * @throws ConflictException <code>rev</code> is not a current revision for this document
     * @throws AttachmentException if there was an error saving the attachments
     * @throws DocumentStoreException if there was an error updating the document
     * @see Database#getEventBus()
     */
    DocumentRevision update(DocumentRevision rev) throws ConflictException,
            AttachmentException, DocumentStoreException;

    /**
     * <p>Deletes a document from the datastore.</p>
     *
     * <p>This operation leaves a "tombstone" for the deleted document, so that
     * future replication operations can successfully replicate the deletion.
     * </p>
     *
     * <p>If the document is successfully deleted, a
     * {@link com.cloudant.sync.event.notifications.DocumentDeleted DocumentDeleted}
     * event is posted on the event bus.</p>
     *
     * <p>If the input revision is already deleted, nothing will be changed. </p>
     *
     * <p>When resolving conflicts, this method can be used to delete any non-deleted
     * leaf revision of a document. {@link Database#resolveConflicts} handles this
     * deletion step during conflict resolution, so it's not usually necessary to call this
     * method in this way from client code. See the doc/conflicts.md document for
     * more details.</p>
     *
     * @param rev the <code>DocumentRevision</code> to be deleted
     * @return a <code>DocumentRevision</code> - the deleted or "tombstone" document
     * @throws ConflictException if the <code>sourceRevisionId</code> is not the current revision
     * @throws DocumentNotFoundException if the <code>DocumentRevision</code> was already deleted
     * @throws DocumentStoreException if there was an error deleting the document
     * @see Database#getEventBus()
     * @see Database#resolveConflicts
     */
    DocumentRevision delete(DocumentRevision rev) throws ConflictException, DocumentNotFoundException,
            DocumentStoreException;

    /**
     * <p>Delete all leaf revisions for the document</p>
     *
     * <p>This is equivalent to calling
     * {@link Database#delete(DocumentRevision)
     * delete} on all leaf revisions</p>
     *
     * @param id the ID of the document to delete leaf nodes for
     * @return a List of a <code>DocumentRevision</code>s - the deleted or "tombstone" documents
     * @throws DocumentStoreException if there was an error deleting the document
     * @see Database#getEventBus()
     * @see Database#delete(DocumentRevision)
     */
    List<DocumentRevision> delete(String id) throws DocumentStoreException;

    /**
     * Compacts the SQL database and disk storage by removing the bodies and attachments of obsolete revisions.
     */
    void compact() throws DocumentStoreException;

    /**
     * <p>Returns the EventBus which this Datastore posts
     * {@link com.cloudant.sync.event.notifications.DocumentModified Document Notification Events} to.</p>
     * @return the Datastore's EventBus
     *
     * @see <a href="https://github.com/cloudant/sync-android/blob/master/doc/events.md">
     *     Events documentation</a>
     */
    EventBus getEventBus();


}

