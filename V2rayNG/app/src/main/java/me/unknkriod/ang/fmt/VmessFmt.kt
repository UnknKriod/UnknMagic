package me.unknkriod.ang.fmt

import android.text.TextUtils
import me.unknkriod.ang.AppConfig
import me.unknkriod.ang.dto.ProfileItem
import me.unknkriod.ang.dto.VmessQRCode
import me.unknkriod.ang.enums.EConfigType
import me.unknkriod.ang.enums.NetworkType
import me.unknkriod.ang.extension.idnHost
import me.unknkriod.ang.extension.nullIfBlank
import me.unknkriod.ang.handler.MmkvManager
import me.unknkriod.ang.util.JsonUtil
import me.unknkriod.ang.util.LogUtil
import me.unknkriod.ang.util.Utils
import java.net.URI

object VmessFmt : FmtBase() {
    /**
     * Parses a Vmess string into a ProfileItem object.
     *
     * @param str the Vmess string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        if (str.indexOf('?') > 0 && str.indexOf('&') > 0) {
            return parseVmessStd(str)
        }

        val allowInsecure = MmkvManager.decodeSettingsBool(AppConfig.PREF_ALLOW_INSECURE, false)
        val config = ProfileItem.Companion.create(EConfigType.VMESS)

        var result = str.replace(EConfigType.VMESS.protocolScheme, "")
        result = Utils.decode(result)
        if (TextUtils.isEmpty(result)) {
            LogUtil.w(AppConfig.TAG, "Toast decoding failed")
            return null
        }
        val vmessQRCode = JsonUtil.fromJson(result, VmessQRCode::class.java) ?: return null
        // Although VmessQRCode fields are non null, looks like Gson may still create null fields
        if (TextUtils.isEmpty(vmessQRCode.add)
            || TextUtils.isEmpty(vmessQRCode.port)
            || TextUtils.isEmpty(vmessQRCode.id)
            || TextUtils.isEmpty(vmessQRCode.net)
        ) {
            LogUtil.w(AppConfig.TAG, "Toast incorrect protocol")
            return null
        }

        config.remarks = vmessQRCode.ps
        config.server = vmessQRCode.add
        config.serverPort = vmessQRCode.port
        config.password = vmessQRCode.id
        config.method =
            if (TextUtils.isEmpty(vmessQRCode.scy)) AppConfig.DEFAULT_SECURITY else vmessQRCode.scy

        config.network = vmessQRCode.net
        if (config.network.isNullOrEmpty()) {
            config.network = NetworkType.TCP.type
        }
        config.headerType = vmessQRCode.type
        config.host = vmessQRCode.host
        config.path = vmessQRCode.path

        when (NetworkType.Companion.fromString(config.network)) {
            NetworkType.KCP -> {
                config.seed = vmessQRCode.path
            }

//            NetworkType.QUIC -> {
//                config.quicSecurity = vmessQRCode.host
//                config.quicKey = vmessQRCode.path
//            }

            NetworkType.GRPC -> {
                config.mode = vmessQRCode.type
                config.serviceName = vmessQRCode.path
                config.authority = vmessQRCode.host
            }

            else -> {}
        }

        config.security = vmessQRCode.tls
        config.sni = vmessQRCode.sni
        config.fingerPrint = vmessQRCode.fp
        config.alpn = vmessQRCode.alpn
        config.insecure = when (vmessQRCode.insecure) {
            "1" -> true
            "0" -> false
            else -> allowInsecure
        }
        return config
    }

    /**
     * Converts a ProfileItem object to a URI string.
     *
     * @param config the ProfileItem object to convert
     * @return the converted URI string
     */
    fun toUri(config: ProfileItem): String {
        val vmessQRCode = VmessQRCode()

        vmessQRCode.v = "2"
        vmessQRCode.ps = config.remarks
        vmessQRCode.add = config.server.orEmpty()
        vmessQRCode.port = config.serverPort.orEmpty()
        vmessQRCode.id = config.password.orEmpty()
        vmessQRCode.scy = config.method.orEmpty()
        vmessQRCode.aid = "0"

        vmessQRCode.net = config.network.orEmpty()
        vmessQRCode.type = config.headerType.orEmpty()
        when (NetworkType.Companion.fromString(config.network)) {
            NetworkType.KCP -> {
                vmessQRCode.path = config.seed.orEmpty()
            }

//            NetworkType.QUIC -> {
//                vmessQRCode.host = config.quicSecurity.orEmpty()
//                vmessQRCode.path = config.quicKey.orEmpty()
//            }

            NetworkType.GRPC -> {
                vmessQRCode.type = config.mode.orEmpty()
                vmessQRCode.path = config.serviceName.orEmpty()
                vmessQRCode.host = config.authority.orEmpty()
            }

            else -> {}
        }

        config.host?.nullIfBlank()?.let { vmessQRCode.host = it }
        config.path?.nullIfBlank()?.let { vmessQRCode.path = it }

        vmessQRCode.tls = config.security.orEmpty()
        vmessQRCode.sni = config.sni.orEmpty()
        vmessQRCode.fp = config.fingerPrint.orEmpty()
        vmessQRCode.alpn = config.alpn.orEmpty()
        vmessQRCode.insecure = when (config.insecure) {
            true -> "1"
            false -> "0"
            else -> ""
        }

        val json = JsonUtil.toJson(vmessQRCode)
        return Utils.encode(json)
    }

    /**
     * Parses a standard Vmess URI string into a ProfileItem object.
     *
     * @param str the standard Vmess URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parseVmessStd(str: String): ProfileItem? {
        val allowInsecure = MmkvManager.decodeSettingsBool(AppConfig.PREF_ALLOW_INSECURE, false)
        val config = ProfileItem.Companion.create(EConfigType.VMESS)

        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.rawQuery.isNullOrEmpty()) return null
        val queryParam = getQueryParam(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()
        config.password = uri.userInfo
        config.method = AppConfig.DEFAULT_SECURITY

        getItemFormQuery(config, queryParam, allowInsecure)

        return config
    }


}