import 'dart:convert';
import 'dart:io';

import 'package:better_player_plus/src/asms/better_player_asms_data_holder.dart';
import 'package:better_player_plus/src/core/better_player_utils.dart';
import 'package:better_player_plus/src/hls/better_player_hls_utils.dart';

///Base helper class for ASMS parsing.
class BetterPlayerAsmsUtils {
  const BetterPlayerAsmsUtils._();

  static const String _hlsExtension = 'm3u8';
  static const String _dashExtension = 'mpd';

  static final HttpClient _httpClient = HttpClient()
    ..connectionTimeout = const Duration(seconds: 5)
    ..badCertificateCallback = allowIpHostCertMismatch;

  /// Accept a TLS certificate whose hostname doesn't match ONLY when the host
  /// is a bare IP literal. This is the `dns_auto` case: the stream is pinned
  /// to an IP, so the server's (valid, CA-signed) domain certificate can never
  /// match the IP. Named hosts keep full verification — a real cert error on a
  /// domain still rejects, so normal HTTPS protection is unchanged.
  static bool allowIpHostCertMismatch(X509Certificate cert, String host, int port) =>
      InternetAddress.tryParse(host) != null;

  ///Check if given url is HLS / DASH-type data source.
  static bool isDataSourceAsms(String url) => isDataSourceHls(url) || isDataSourceDash(url);

  ///Check if given url is HLS-type data source.
  static bool isDataSourceHls(String url) => url.contains(_hlsExtension);

  ///Check if given url is DASH-type data source.
  static bool isDataSourceDash(String url) => url.contains(_dashExtension);

  /// Parse playlist based on type of stream.
  ///
  /// The HLS parser will throw `SignalException: Input does not start
  /// with the #EXTM3U header` on a DASH MPD, log it, and return empty.
  /// That used to be the only path, but DASH track lists now come in via
  /// the native `tracksChanged` event (see BetterPlayer.kt), so the
  /// throw + log is pure noise. Skip the HLS parse entirely for DASH and
  /// return an empty holder — the native event will populate
  /// `_betterPlayerAsmsTracks` / `_betterPlayerAsmsAudioTracks` once the
  /// player parses the manifest.
  static Future<BetterPlayerAsmsDataHolder> parse(String data, String masterPlaylistUrl) {
    if (isDataSourceDash(masterPlaylistUrl)) {
      return Future.value(BetterPlayerAsmsDataHolder());
    }
    return BetterPlayerHlsUtils.parse(data, masterPlaylistUrl);
  }

  ///Request data from given uri along with headers. May return null if resource
  ///is not available or on error.
  static Future<String?> getDataFromUrl(String url, [Map<String, String?>? headers]) async {
    try {
      final request = await _httpClient.getUrl(Uri.parse(url));
      if (headers != null) {
        headers.forEach((name, value) => request.headers.add(name, value!));
      }

      final response = await request.close();
      var data = '';
      await response.transform(const Utf8Decoder()).listen((content) {
        data += content;
      }).asFuture<String?>();

      return data;
    } on Exception catch (exception) {
      BetterPlayerUtils.log('GetDataFromUrl failed: $exception');
      return null;
    }
  }
}
