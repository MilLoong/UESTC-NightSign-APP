class WeChatError(RuntimeError):
    pass


class WeChatAuthorizationError(WeChatError):
    pass


class WeChatProtocolError(WeChatError):
    pass
