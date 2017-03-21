/**
 * Copyright (C) 2013 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.util

import java.io.ByteArrayInputStream

import org.apache.commons.fileupload.{FileItem, UploadContext}
import org.orbeon.datatypes.MaximumSize
import org.orbeon.datatypes.MaximumSize.LimitedSize
import org.orbeon.io.LimiterInputStream
import org.orbeon.oxf.externalcontext.ExternalContext.Session
import org.orbeon.oxf.externalcontext.{ExternalContext, TestSession}
import org.orbeon.oxf.resources.ResourceManagerWrapper
import org.orbeon.oxf.test.ResourceManagerSupport
import org.orbeon.oxf.util.Multipart._
import org.orbeon.oxf.util.NetUtils.inputStreamToByteArray
import org.orbeon.oxf.xforms.upload.UploaderServer
import org.orbeon.oxf.xforms.upload.UploaderServer.UploadProgressMultipartLifecycle
import org.scalatest.FunSpecLike

import scala.collection.{mutable ⇒ m}


class MultipartTest extends ResourceManagerSupport with FunSpecLike {

  val rm = ResourceManagerWrapper.instance

  val body       = inputStreamToByteArray(rm.getContentAsStream("/org/orbeon/oxf/util/multipart-request.bin"))
  val miserables = inputStreamToByteArray(rm.getContentAsStream("/org/orbeon/oxf/util/miserables-8000.txt"))

  assert(body.length       == 8326)
  assert(miserables.length == 8000)

  val UUID = "3160b6fe0df34b94ee31a19cb1f79c95b6351441"
  val FieldName = "xf-5"

  // NOTE: Use `WrappedArray` so that `Array` content comparison works
  case class FileItemContent(contentType: String, fieldName: String, size: Long, filename: String, content: m.WrappedArray[Byte])

  def convertFileItemContent(a: AnyRef) = a match {
    case f: FileItem ⇒ FileItemContent(f.getContentType, f.getFieldName, f.getSize, f.getName, inputStreamToByteArray(f.getInputStream))
    case other       ⇒ other
  }

  def newTrustedUploadContext(body: Array[Byte]) = new UploadContext {

    private val outerLimiterInputStream = new LimiterInputStream(
      new ByteArrayInputStream(body),
      LimitedSize(Multipart.DefaultBufferSize), // should be enough to read headers and `$uuid` (updated once first file is detected)
      Multipart.throwSizeLimitExceeded
    )

    val getContentType       = "multipart/form-data; boundary=----WebKitFormBoundaryicZu65oDuNAzes9o"
    val getCharacterEncoding = null
    val getInputStream       = outerLimiterInputStream

    // Set to -1 because we want to be able to read at least `$uuid`
    val contentLength        = -1L
    def getContentLength     = -1 // this won't be used anyway
  }

  def newRead(session: Session, maxSize: Long) = {

    val uploadContext = newTrustedUploadContext(body)

    val (pairs, throwableOpt) =
      parseMultipartRequest(
        uploadContext,
        Some(
          new UploadProgressMultipartLifecycle(Some(body.length.toLong), uploadContext.getInputStream, session) {
            def getMaxUploadSizeForControl(uuid: String, controlName: String): MaximumSize =
              MaximumSize.tryFromString(maxSize.toString).get
          }
        ),
        MaximumSize.tryFromString(maxSize.toString) getOrElse LimitedSize(0L),
        ExternalContext.StandardHeaderCharacterEncoding
      )

    (pairs map { case (a, b) ⇒ a → convertFileItemContent(b) }, throwableOpt map (_.getClass.getName))
  }

  describe("Parsing a multipart request which doesn't exceed the maximum size specified") {

    val MustSucceedWithLimits = List(-1L, 8326L, 10000L)

    val expectedPairs = List(
      "$uuid"   → UUID,
      FieldName → FileItemContent("text/plain", FieldName, 8000L, "miserables-8000.txt", miserables)
    )

    for (limit ← MustSucceedWithLimits) {
      describe(s"with limit $limit") {

        val session = new TestSession(SecureUtils.randomHexId)

        it("must return UUID and file result") {
          assert((expectedPairs, None) === newRead(session, limit))
        }

        it("must set completed `UploadProgress` into session") {
          assert(
            Some(UploadProgress(FieldName, Some(body.length), miserables.length, Completed)) ===
              UploaderServer.getUploadProgressFromSession(Some(session), UUID, FieldName)
          )
        }
      }
    }
  }

  describe("Parsing a multipart request which exceeds the maximum size specified") {

    val MustFailWithLimits = List(0, 4097, 8000)// NOTE: any value under 4096 is the same as 4096 (buffer size)

    val expectedPairs = List(
      "$uuid" → UUID
    )

    for (limit ← MustFailWithLimits) {
      describe(s"with limit $limit") {

        val session = new TestSession(SecureUtils.randomHexId)

        it("must return just the UUID") {
          assert(
            (expectedPairs, Some("org.apache.commons.fileupload.FileUploadBase$SizeLimitExceededException")) ===
              newRead(session, limit)
          )
        }

        it("must set `Interrupted` `UploadProgress` into session") {
          assert(
            Some(UploadProgress(FieldName, Some(body.length), 0, Interrupted(Some(SizeReason(limit, 8326))))) ===
              UploaderServer.getUploadProgressFromSession(Some(session), UUID, FieldName)
          )
        }
      }
    }
  }
}
