package com.garfiec.librechat.core.model.response

import kotlinx.serialization.Serializable

/**
 * Response of `POST /api/auth/register`. The backend returns only `{ message }`
 * (`upstream/api/server/controllers/AuthController.js` `registrationController` →
 * `res.status(status).send({ message })`) — it has never returned a `user` object. The field is
 * nullable so a successful registration decodes even if the backend omits the message.
 */
@Serializable
data class RegisterResponse(
    val message: String? = null,
)
