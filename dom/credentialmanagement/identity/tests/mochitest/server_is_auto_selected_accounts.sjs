/* Any copyright is dedicated to the Public Domain.
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

function handleRequest(request, response) {
  if (
    !request.hasHeader("Cookie") ||
    request.getHeader("Cookie") != "credential=authcookieval"
  ) {
    response.setStatusLine(request.httpVersion, 400, "Bad Request");
    return;
  }
  if (
    !request.hasHeader("Sec-Fetch-Dest") ||
    request.getHeader("Sec-Fetch-Dest") != "webidentity"
  ) {
    response.setStatusLine(request.httpVersion, 400, "Bad Request");
    return;
  }
  if (request.hasHeader("Origin") && request.getHeader("Origin") != "null") {
    response.setStatusLine(request.httpVersion, 400, "Bad Request");
    return;
  }
  if (request.hasHeader("Referer")) {
    response.setStatusLine(request.httpVersion, 400, "Bad Request");
    return;
  }

  response.setHeader("Access-Control-Allow-Origin", "*");
  response.setHeader("Access-Control-Allow-Credentials", "true");
  response.setHeader("Content-Type", "application/json");
  let content = {
    accounts: [
      {
        id: "1234",
        given_name: "John",
        name: "John Doe",
        email: "john_doe@example.com",
        picture: "https://example.com/profile/123",
        approved_clients: ["123", "456", "789", "mochitest"],
      },
    ],
  };
  let body = JSON.stringify(content);
  response.setStatusLine(request.httpVersion, 200, "OK");
  response.write(body);
}
