package dev.brahmkshatriya.echo.extensions.db.models

import androidx.room.Entity
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import dev.brahmkshatriya.echo.utils.crypto.SecretStores

@Entity(primaryKeys = ["id", "type", "extId"])
data class UserEntity(
    val type: ExtensionType,
    val extId: String,
    val id: String,
    val data: String
) {
    // Encrypted at rest; legacy plaintext rows still decode.
    val user by lazy {
        val plain = SecretStores.current?.decrypt(data) ?: data
        plain.toData<User>()
    }

    companion object {
        fun User.toEntity(type: ExtensionType, clientId: String): UserEntity? {
            val encrypted = SecretStores.current?.encrypt(toJson()) ?: return null
            return UserEntity(type, clientId, id, encrypted)
        }

        fun UserEntity.toCurrentUser() =
            CurrentUser(type, extId, id)
    }
}
