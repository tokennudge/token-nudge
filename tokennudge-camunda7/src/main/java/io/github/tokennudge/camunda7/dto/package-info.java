/**
 * Data-transfer records mirroring engine-rest JSON request and response bodies, used only by
 * {@code io.github.tokennudge.camunda7}'s transport layer.
 *
 * <p>Internal by convention: nothing here is part of the public API. The records are
 * {@code public} only so that this package can be kept separate from
 * {@code io.github.tokennudge.camunda7}; Jackson itself does not require public visibility
 * to serialize or deserialize a record (it makes the canonical constructor and accessors
 * accessible reflectively regardless of their declared visibility), so this is purely an
 * organizational choice, not a Jackson requirement.
 */
package io.github.tokennudge.camunda7.dto;
