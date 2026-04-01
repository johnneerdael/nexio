from __future__ import annotations

import collections
import json

from yt_dlp.extractor.youtube.jsc.provider import (
    JsChallengeProvider,
    JsChallengeProviderError,
    JsChallengeProviderRejectedRequest,
    JsChallengeProviderResponse,
    JsChallengeResponse,
    JsChallengeType,
    NChallengeOutput,
    SigChallengeOutput,
    register_preference,
    register_provider,
)
from yt_dlp.extractor.youtube.pot._provider import BuiltinIEContentProvider


def _get_bridge():
    try:
        from java import jclass
    except Exception:
        return None

    try:
        return jclass('com.nexio.tv.data.trailer.helper.YouTubeJavaScriptEngineBridge')
    except Exception:
        return None


@register_provider
class AndroidJSEngineJCP(JsChallengeProvider, BuiltinIEContentProvider):
    _SUPPORTED_TYPES = [JsChallengeType.N, JsChallengeType.SIG]

    def is_available(self) -> bool:
        bridge = _get_bridge()
        if not bridge:
            return False
        return bool(bridge.isReady())

    def _real_bulk_solve(self, requests):
        bridge = _get_bridge()
        if not bridge or not bridge.isReady():
            raise JsChallengeProviderRejectedRequest('Android JavaScriptEngine bridge is not ready')

        grouped = collections.defaultdict(list)
        for request in requests:
            grouped[request.input.player_url].append(request)

        for player_url, grouped_requests in grouped.items():
            video_id = next((request.video_id for request in grouped_requests), None)
            player = self._get_player(video_id, player_url)
            self.logger.info('Solving JS challenges using android_js_engine')

            payload = {
                'type': 'player',
                'player': player,
                'requests': [
                    {
                        'type': request.type.value,
                        'challenges': request.input.challenges,
                    }
                    for request in grouped_requests
                ],
                'output_preprocessed': False,
            }

            try:
                output = json.loads(bridge.solveSerialized(json.dumps(payload)))
            except Exception as error:
                raise JsChallengeProviderError(f'Android JavaScriptEngine evaluation failed: {error!r}') from error

            if output.get('type') == 'error':
                raise JsChallengeProviderError(output.get('error') or 'Android JavaScriptEngine returned an error')

            for request, response_data in zip(grouped_requests, output.get('responses', []), strict=True):
                if response_data.get('type') == 'error':
                    yield JsChallengeProviderResponse(
                        request=request,
                        error=JsChallengeProviderError(response_data.get('error') or 'Unknown Android solver error', expected=True),
                    )
                    continue

                data = response_data.get('data') or {}
                yield JsChallengeProviderResponse(
                    request=request,
                    response=JsChallengeResponse(
                        request.type,
                        NChallengeOutput(data) if request.type is JsChallengeType.N else SigChallengeOutput(data),
                    ),
                )


@register_preference(AndroidJSEngineJCP)
def preference(provider, requests) -> int:
    return 1100
