import pytest

URL = "https://trafficnews.jp/"
DESKTOP_CSS = "#pickup-slider"
MOBILE_CSS = "#main-column"


@pytest.mark.only_platforms("android")
@pytest.mark.asyncio
@pytest.mark.with_interventions
async def test_enabled(client):
    await client.navigate(URL, wait="none")
    assert client.await_css(MOBILE_CSS, is_displayed=True)
    assert not client.find_css(DESKTOP_CSS, is_displayed=True)


@pytest.mark.only_platforms("android")
@pytest.mark.asyncio
@pytest.mark.without_interventions
async def test_disabled(client):
    await client.navigate(URL, wait="none")
    assert client.await_css(DESKTOP_CSS, is_displayed=True)
    assert not client.find_css(MOBILE_CSS, is_displayed=True)
