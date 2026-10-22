# FastAPI Framework Guide

## Introduction to FastAPI

FastAPI is a modern, fast (high-performance) web framework for building APIs with Python 3.7+ based on standard Python type hints. It's designed to be easy to use while also being extremely performant.

## Key Features

### Automatic API Documentation

FastAPI automatically generates interactive API documentation:
- **Swagger UI**: Available at `/docs`
- **ReDoc**: Available at `/redoc`

### Type Hints and Validation

FastAPI uses Python type hints for automatic request validation and serialization.

```python
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI()

class Item(BaseModel):
    name: str
    price: float
    is_offer: bool = False

@app.post("/items/")
async def create_item(item: Item):
    return {"item_name": item.name, "item_price": item.price}
```

### Async Support

FastAPI is built on top of Starlette and supports async/await natively:

```python
@app.get("/async-example")
async def async_endpoint():
    result = await some_async_operation()
    return {"result": result}
```

## Core Concepts

### Path Operations

Path operations are functions that handle HTTP requests:

```python
@app.get("/users/{user_id}")
async def read_user(user_id: int):
    return {"user_id": user_id}

@app.post("/users/")
async def create_user(user: User):
    return user

@app.put("/users/{user_id}")
async def update_user(user_id: int, user: User):
    return {"user_id": user_id, **user.dict()}

@app.delete("/users/{user_id}")
async def delete_user(user_id: int):
    return {"deleted": user_id}
```

### Query Parameters

Query parameters are automatically parsed from the URL:

```python
@app.get("/items/")
async def read_items(skip: int = 0, limit: int = 10, q: str = None):
    items = get_items(skip=skip, limit=limit)
    if q:
        items = filter_items(items, q)
    return items
```

### Request Body

Use Pydantic models to define request bodies:

```python
from pydantic import BaseModel, Field

class CreateUserRequest(BaseModel):
    email: str = Field(..., description="User's email address")
    password: str = Field(..., min_length=8)
    name: str = Field(None, max_length=100)

@app.post("/register")
async def register(request: CreateUserRequest):
    # Password will be at least 8 characters (validated automatically)
    return {"email": request.email, "name": request.name}
```

### Dependency Injection

FastAPI has a powerful dependency injection system:

```python
from fastapi import Depends

def get_db():
    db = DatabaseSession()
    try:
        yield db
    finally:
        db.close()

def get_current_user(token: str = Header(...)):
    user = decode_token(token)
    return user

@app.get("/profile")
async def get_profile(
    user = Depends(get_current_user),
    db = Depends(get_db)
):
    return db.query(User).filter(User.id == user.id).first()
```

## Routers

Organize your API into modules using APIRouter:

```python
# routers/users.py
from fastapi import APIRouter

router = APIRouter(prefix="/users", tags=["users"])

@router.get("/")
async def list_users():
    return []

@router.get("/{user_id}")
async def get_user(user_id: int):
    return {"user_id": user_id}

# main.py
from fastapi import FastAPI
from routers import users

app = FastAPI()
app.include_router(users.router)
```

## Middleware

Add middleware for cross-cutting concerns:

```python
from fastapi.middleware.cors import CORSMiddleware

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.middleware("http")
async def add_process_time_header(request, call_next):
    start_time = time.time()
    response = await call_next(request)
    process_time = time.time() - start_time
    response.headers["X-Process-Time"] = str(process_time)
    return response
```

## Error Handling

Handle errors gracefully with exception handlers:

```python
from fastapi import HTTPException
from fastapi.responses import JSONResponse

class CustomException(Exception):
    def __init__(self, message: str, code: str):
        self.message = message
        self.code = code

@app.exception_handler(CustomException)
async def custom_exception_handler(request, exc):
    return JSONResponse(
        status_code=400,
        content={"error": exc.message, "code": exc.code}
    )

@app.get("/item/{item_id}")
async def get_item(item_id: int):
    if item_id not in items:
        raise HTTPException(status_code=404, detail="Item not found")
    return items[item_id]
```

## Background Tasks

Execute tasks in the background after returning a response:

```python
from fastapi import BackgroundTasks

def send_email(email: str, message: str):
    # Simulate sending email
    time.sleep(5)
    print(f"Email sent to {email}")

@app.post("/subscribe")
async def subscribe(email: str, background_tasks: BackgroundTasks):
    background_tasks.add_task(send_email, email, "Welcome!")
    return {"message": "Subscription confirmed"}
```

## Best Practices

1. **Use Pydantic models** for all request/response schemas
2. **Organize with routers** for maintainable code structure
3. **Implement proper error handling** with custom exceptions
4. **Use dependency injection** for shared resources
5. **Add comprehensive logging** for debugging
6. **Write tests** using TestClient from fastapi.testclient
7. **Document your API** with descriptions in path operations

