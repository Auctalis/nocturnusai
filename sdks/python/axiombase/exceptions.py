"""Exception types for the AxiomBase Python SDK."""

from __future__ import annotations

from typing import Any


class AxiomBaseError(Exception):
    """Base exception for all AxiomBase SDK errors."""

    def __init__(self, message: str) -> None:
        self.message = message
        super().__init__(message)


class AxiomBaseAPIError(AxiomBaseError):
    """Raised when the AxiomBase server returns an error response.

    Attributes:
        status_code: The HTTP status code returned by the server.
        error_code: The machine-readable error code from the response body.
        message: The human-readable error message.
        details: Optional additional error details.
    """

    def __init__(
        self,
        message: str,
        status_code: int,
        error_code: str | None = None,
        details: dict[str, Any] | None = None,
    ) -> None:
        self.status_code = status_code
        self.error_code = error_code
        self.details = details
        super().__init__(message)

    def __str__(self) -> str:
        parts = [f"[HTTP {self.status_code}]"]
        if self.error_code:
            parts.append(f"[{self.error_code}]")
        parts.append(self.message)
        return " ".join(parts)


class AxiomBaseConnectionError(AxiomBaseError):
    """Raised when the client cannot connect to the AxiomBase server."""


class AxiomBaseTimeoutError(AxiomBaseError):
    """Raised when a request to the AxiomBase server times out."""


class AxiomBaseValidationError(AxiomBaseError):
    """Raised when request validation fails on the server side (HTTP 400)."""

    def __init__(self, message: str, details: dict[str, Any] | None = None) -> None:
        self.details = details
        super().__init__(message)


class AxiomBaseConflictError(AxiomBaseError):
    """Raised when a fact assertion causes a contradiction (HTTP 409)."""


class AxiomBaseNotFoundError(AxiomBaseError):
    """Raised when a requested database or resource is not found (HTTP 404)."""
